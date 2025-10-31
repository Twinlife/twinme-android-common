/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.TransientObjectDescriptor;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.RoomCommandResult;
import org.twinlife.twinme.models.RoomConfig;
import org.twinlife.twinme.models.RoomConfigResult;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;
import java.util.UUID;

public class EditRoomService extends AbstractTwinmeService {
    private static final String LOG_TAG = "EditRoomService";
    private static final boolean DEBUG = false;

    private static final int GET_ROOM = 1;
    private static final int GET_ROOM_DONE = 1 << 2;
    private static final int UPDATE_ROOM_NAME = 1 << 3;
    private static final int UPDATE_ROOM_NAME_DONE = 1 << 4;
    private static final int UPDATE_ROOM_AVATAR = 1 << 5;
    private static final int UPDATE_ROOM_AVATAR_DONE = 1 << 6;
    private static final int UPDATE_ROOM_WELCOME_MESSAGE = 1 << 7;
    private static final int UPDATE_ROOM_WELCOME_MESSAGE_DONE = 1 << 8;
    private static final int DELETE_ROOM = 1 << 9;
    private static final int DELETE_ROOM_DONE = 1 << 10;
    private static final int GET_ROOM_CONFIG = 1 << 11;
    private static final int GET_ROOM_CONFIG_DONE = 1 << 12;
    private static final int SET_ROOM_CONFIG = 1 << 13;
    private static final int SET_ROOM_CONFIG_DONE = 1 << 14;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onGetRoomConfig(@NonNull RoomConfig roomConfig);

        void onGetRoomConfigNotFound();
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (!mRoomId.equals(contact.getId())) {

                return;
            }

            if (getOperation(requestId) != null) {
                EditRoomService.this.onUpdateContact(contact);
            }

            // May be we have received the private peer twincode and we can proceed with other operations.
            onOperation();
        }

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            if (!mRoomId.equals(contactId)) {

                return;
            }

            finishOperation(requestId);
            EditRoomService.this.onDeleteContact(contactId);
        }
    }

    private class ConversationServiceObserver extends org.twinlife.twinlife.ConversationService.DefaultServiceObserver {

        @Override
        public void onPopDescriptor(long requestId, @NonNull org.twinlife.twinlife.ConversationService.Conversation conversation, @NonNull org.twinlife.twinlife.ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPopDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!(descriptor instanceof TransientObjectDescriptor)) {
                return;
            }

            TransientObjectDescriptor command = (TransientObjectDescriptor) descriptor;
            Object object = command.getObject();
            if (!(object instanceof RoomCommandResult)) {
                return;
            }

            RoomCommandResult result = (RoomCommandResult) object;
            if (result.getRequestId() != mRoomRequestId) {
                return;
            }

            long operationId = getOperation(mRoomRequestId);
            EditRoomService.this.onRoomCommandResult(result, operationId);
            onOperation();
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            EditRoomService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @NonNull
    private final ConversationServiceObserver mConversationServiceObserver;
    private ConversationService mConversationService;
    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    @NonNull
    private final UUID mRoomId;
    @Nullable
    private Contact mRoom;
    private Bitmap mAvatar;
    private File mAvatarFile;
    private String mName;
    private String mWelcomeMessage;
    private RoomConfig mRoomConfig;
    private long mRoomRequestId;

    public EditRoomService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
                           @NonNull UUID roomId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "EditRoomService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mRoomId = roomId;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        mConversationService = mTwinmeContext.getConversationService();
        mConversationService.addServiceObserver(mConversationServiceObserver);
        super.onTwinlifeReady();
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife() && mConversationService != null) {
            mConversationService.removeServiceObserver(mConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    public void getRoomConfig() {

        mWork |= GET_ROOM_CONFIG;
        mState &= ~(GET_ROOM_CONFIG | GET_ROOM_CONFIG_DONE);

        showProgressIndicator();
        startOperation();
    }

    public void updateRoom(@NonNull Contact room, @NonNull String name, @NonNull Bitmap avatar, @Nullable File avatarFile,
                           @Nullable String welcomeMessage) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateRoom: room=" + room + " name=" + name + " avatar=" + avatar);
        }

        mRoom = room;
        mName = name;
        mAvatar = avatar;
        mAvatarFile = avatarFile;
        mWelcomeMessage = welcomeMessage;

        if (!name.equals(mRoom.getName())) {
            mWork |= UPDATE_ROOM_NAME;
            mState &= ~(UPDATE_ROOM_NAME | UPDATE_ROOM_NAME_DONE);
        } else if (mAvatar != null) {
            mWork |= UPDATE_ROOM_AVATAR;
            mState &= ~(UPDATE_ROOM_AVATAR | UPDATE_ROOM_AVATAR_DONE);
        } else if (mWelcomeMessage != null) {
            mWork |= UPDATE_ROOM_WELCOME_MESSAGE;
            mState &= ~(UPDATE_ROOM_WELCOME_MESSAGE | UPDATE_ROOM_WELCOME_MESSAGE_DONE);
        }

        showProgressIndicator();
        startOperation();
    }

    public void deleteRoom(@NonNull Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteRoom: room=" + room);
        }

        long requestId = newOperation(DELETE_ROOM);
        if (DEBUG) {
            Log.d(LOG_TAG, "TwinmeContext.deleteContact: requestId=" + requestId + " contact=" + room);
        }
        showProgressIndicator();

        mTwinmeContext.deleteContact(requestId, room);
    }

    public void updateRoomConfig(@NonNull Contact room, @NonNull RoomConfig roomConfig) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateRoomConfig: room=" + room + " roomConfig=" + roomConfig);
        }

        mRoom = room;
        mRoomConfig = roomConfig;

        mWork |= SET_ROOM_CONFIG;
        mState &= ~(SET_ROOM_CONFIG | SET_ROOM_CONFIG_DONE);

        showProgressIndicator();
        startOperation();
    }

    //
    // Private methods
    //
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: Get the current room.
        //
        if ((mState & GET_ROOM) == 0) {
            mState |= GET_ROOM;

            mTwinmeContext.getContact(mRoomId, this::onGetRoom);
            return;
        }
        if ((mState & GET_ROOM_DONE) == 0) {
            return;
        }

        //
        // Work step: we must delete the room (it must be possible even if we don't have the private peer!).
        //
        if (mRoom != null && (mWork & DELETE_ROOM) != 0) {
            if ((mState & DELETE_ROOM) == 0) {
                mState |= DELETE_ROOM;
                long requestId = newOperation(DELETE_ROOM);
                mTwinmeContext.deleteContact(requestId, mRoom);
                return;
            }
            if ((mState & DELETE_ROOM_DONE) == 0) {
                return;
            }
        }

        // If the room has no private peer yet, we cannot make API requests on it.
        // Every step below requires a valid private peer.
        if (mRoom != null && !mRoom.hasPrivatePeer()) {
            return;
        }

        //
        // Step 2: Get the current room config.
        //
        if ((mWork & GET_ROOM_CONFIG) != 0 && mRoom != null) {
            if ((mState & GET_ROOM_CONFIG) == 0) {
                mState |= GET_ROOM_CONFIG;
                long requestId = newOperation(GET_ROOM_CONFIG);
                mRoomRequestId = requestId;
                mTwinmeContext.roomGetConfig(requestId, mRoom);
                return;
            }
            if ((mState & GET_ROOM_CONFIG_DONE) == 0) {
                return;
            }
        }

        // We must update room name
        if ((mWork & UPDATE_ROOM_NAME) != 0 && mRoom != null) {
            if ((mState & UPDATE_ROOM_NAME) == 0) {
                mState |= UPDATE_ROOM_NAME;
                long requestId = newOperation(UPDATE_ROOM_NAME);
                mRoomRequestId = requestId;
                mTwinmeContext.roomSetName(requestId, mRoom, mName);
                return;
            }
            if ((mState & UPDATE_ROOM_NAME_DONE) == 0) {
                return;
            }
        }

        // We must update room avatar
        if ((mWork & UPDATE_ROOM_AVATAR) != 0 && mRoom != null) {
            if ((mState & UPDATE_ROOM_AVATAR) == 0) {
                mState |= UPDATE_ROOM_AVATAR;
                long requestId = newOperation(UPDATE_ROOM_AVATAR);
                mRoomRequestId = requestId;
                mTwinmeContext.roomSetImage(requestId, mRoom, mAvatar, mAvatarFile);
                return;
            }
            if ((mState & UPDATE_ROOM_AVATAR_DONE) == 0) {
                return;
            }
        }

        // We must update room welcome message
        if ((mWork & UPDATE_ROOM_WELCOME_MESSAGE) != 0 && mRoom != null) {
            if ((mState & UPDATE_ROOM_WELCOME_MESSAGE) == 0) {
                mState |= UPDATE_ROOM_WELCOME_MESSAGE;
                long requestId = newOperation(UPDATE_ROOM_WELCOME_MESSAGE);
                mRoomRequestId = requestId;
                mTwinmeContext.roomSetWelcome(requestId, mRoom, mWelcomeMessage);
                return;
            }
            if ((mState & UPDATE_ROOM_WELCOME_MESSAGE_DONE) == 0) {
                return;
            }
        }

        // We must update room config
        if ((mWork & SET_ROOM_CONFIG) != 0 && mRoom != null) {
            if ((mState & SET_ROOM_CONFIG) == 0) {
                mState |= SET_ROOM_CONFIG;
                long requestId = newOperation(SET_ROOM_CONFIG);
                mRoomRequestId = requestId;
                mTwinmeContext.roomSetConfig(requestId, mRoom, mRoomConfig);
                return;
            }
            if ((mState & SET_ROOM_CONFIG_DONE) == 0) {
                return;
            }
        }

        hideProgressIndicator();
    }

    private void onGetRoom(@NonNull ErrorCode errorCode, @Nullable Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoom: room=" + room);
        }

        mState |= GET_ROOM_DONE;
        mRoom = room;

        if (room != null) {
            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, room.getId(), mRoomId);

            Bitmap avatar = getImage(room);
            runOnGetContact(mObserver, room, avatar);
            if (avatar == null && room.getAvatarId() != null) {
                getImageFromServer(room);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_ROOM, errorCode, null);
        }
        onOperation();
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, contact.getId(), mRoomId);

        hideProgressIndicator();

        Bitmap avatar = getImage(contact);
        runOnUpdateContact(mObserver, contact, avatar);
        if (avatar == null && contact.getAvatarId() != null) {
            getImageFromServer(contact);
        }
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        runOnDeleteContact(mObserver, contactId);
        onOperation();
    }

    private void onRoomCommandResult(@NonNull RoomCommandResult result, long operationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRoomCommandResult: result=" + result + " operationId=" + operationId);
        }

        if (operationId == GET_ROOM_CONFIG) {
            mState |= GET_ROOM_CONFIG_DONE;

            RoomConfigResult roomConfigResult = (RoomConfigResult) result;
            runOnUiThread(() -> {
                if (mObserver != null) {
                    if (roomConfigResult.getRoomConfig() != null) {
                        mObserver.onGetRoomConfig(roomConfigResult.getRoomConfig());
                    } else {
                        mObserver.onGetRoomConfigNotFound();
                    }
                }
            });

            return;
        }
        boolean isRoomUpdated = false;

        if (operationId == UPDATE_ROOM_NAME) {
            mState |= UPDATE_ROOM_NAME_DONE;

            if (mAvatar != null) {
                mWork |= UPDATE_ROOM_AVATAR;
                mState &= ~(UPDATE_ROOM_AVATAR | UPDATE_ROOM_AVATAR_DONE);
            } else if (mWelcomeMessage != null) {
                mWork |= UPDATE_ROOM_WELCOME_MESSAGE;
                mState &= ~(UPDATE_ROOM_WELCOME_MESSAGE | UPDATE_ROOM_WELCOME_MESSAGE_DONE);
            } else {
                isRoomUpdated = true;
            }
        } else if (operationId == UPDATE_ROOM_AVATAR) {
            mState |= UPDATE_ROOM_AVATAR_DONE;

            if (mWelcomeMessage != null) {
                mWork |= UPDATE_ROOM_WELCOME_MESSAGE;
                mState &= ~(UPDATE_ROOM_WELCOME_MESSAGE | UPDATE_ROOM_WELCOME_MESSAGE_DONE);
            } else {
                isRoomUpdated = true;
            }
        } else if (operationId == UPDATE_ROOM_WELCOME_MESSAGE) {
            mState |= UPDATE_ROOM_WELCOME_MESSAGE_DONE;
            isRoomUpdated = true;
        } else if (operationId == SET_ROOM_CONFIG) {
            mState |= SET_ROOM_CONFIG_DONE;
            isRoomUpdated = true;
        }

        if (mRoom != null && isRoomUpdated) {
            runOnUpdateContact(mObserver, mRoom, mAvatar);
        }
    }
}
