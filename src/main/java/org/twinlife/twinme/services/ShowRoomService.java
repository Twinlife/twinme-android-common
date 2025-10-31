/*
 *  Copyright (c) 2020-2025 twinlife SA.
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

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.TransientObjectDescriptor;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.RoomCommand;
import org.twinlife.twinme.models.RoomCommandResult;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShowRoomService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ShowRoomService";
    private static final boolean DEBUG = false;

    private static final int GET_ROOM = 1;
    private static final int GET_ROOM_DONE = 1 << 1;
    private static final int GET_ROOM_THUMBNAIL_IMAGE = 1 << 2;
    private static final int GET_ROOM_THUMBNAIL_IMAGE_DONE = 1 << 3;
    private static final int GET_ROOM_IMAGE = 1 << 4;
    private static final int GET_ROOM_IMAGE_DONE = 1 << 5;
    private static final int GET_ROOM_MEMBERS = 1 << 6;
    private static final int GET_ROOM_MEMBERS_DONE = 1 << 7;
    private static final int GET_ROOM_MEMBER = 1 << 8;
    private static final int GET_ROOM_MEMBER_DONE = 1 << 9;
    private static final int GET_ROOM_MEMBER_AVATAR = 1 << 10;
    private static final int GET_ROOM_MEMBER_AVATAR_DONE = 1 << 11;
    private static final int DELETE_ROOM = 1 << 12;
    private static final int DELETE_ROOM_DONE = 1 << 13;

    private static final int MAX_ROOM_MEMBER = 5;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onDeleteContact(@NonNull UUID roomId);

        void onGetRoomMembers(@NonNull List<TwincodeOutbound> members, int memberCount);

        void onGetRoomMemberAvatar(@NonNull TwincodeOutbound twincodeOutbound, @Nullable Bitmap avatar);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            if (!mRoomId.equals(contactId)) {

                return;
            }

            finishOperation(requestId);

            ShowRoomService.this.onDeleteContact(contactId);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (!mRoomId.equals(contact.getId())) {

                return;
            }

            ShowRoomService.this.onUpdateContact(contact);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onPopDescriptor(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
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

            ShowRoomService.this.onRoomCommandResult(result);
        }

        @Override
        public void onError(long requestId, BaseService.ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            ShowRoomService.this.onError(operationId, errorCode, errorParameter);
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
    private long mRoomRequestId;
    private Contact mRoom;
    private ImageId mAvatarId;
    private Bitmap mAvatar;
    private int mMemberCount;
    private List<UUID> mMemberIds;
    private UUID mCurrentRoomMemberId;
    private List<TwincodeOutbound> mRoomMembers;
    private TwincodeOutbound mCurrentTwincodeOutbound;

    public ShowRoomService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
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

    @Override
    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;

            if ((mState & GET_ROOM_MEMBER) != 0 && (mState & GET_ROOM_MEMBER_DONE) == 0) {
                mState &= ~GET_ROOM_MEMBER;
            }
            if ((mState & GET_ROOM_MEMBER_AVATAR) != 0 && (mState & GET_ROOM_MEMBER_AVATAR_DONE) == 0) {
                mState &= ~GET_ROOM_MEMBER_AVATAR;
            }
        }
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

    public void deleteRoom(@NonNull Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteRoom: room=" + room);
        }

        mRoom = room;
        mWork |= DELETE_ROOM;
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
        // Step 2: Get the room thumbnail image if we can.
        //
        if (mAvatarId != null && mAvatar == null) {
            if ((mState & GET_ROOM_THUMBNAIL_IMAGE) == 0) {
                mState |= GET_ROOM_THUMBNAIL_IMAGE;
                mTwinmeContext.getImageService().getImageFromServer(mAvatarId, ImageService.Kind.THUMBNAIL, (ErrorCode errorCode, Bitmap image) -> {
                    mState |= GET_ROOM_THUMBNAIL_IMAGE_DONE;
                    mAvatar = image;
                    if (image != null) {
                        runOnUpdateContact(mObserver, mRoom, image);
                    }
                    onOperation();
                });
                return;
            }
            if ((mState & GET_ROOM_THUMBNAIL_IMAGE_DONE) == 0) {
                return;
            }
        }

        //
        // Step 3: Get the room normal image if we can.
        //
        if (mAvatarId != null) {
            if ((mState & GET_ROOM_IMAGE) == 0) {
                mState |= GET_ROOM_IMAGE;

                mTwinmeContext.getImageService().getImageFromServer(mAvatarId, ImageService.Kind.NORMAL, (ErrorCode errorCode, Bitmap image) -> {
                    mState |= GET_ROOM_IMAGE_DONE;
                    mAvatar = image;
                    if (image != null) {
                        runOnUpdateContact(mObserver, mRoom, image);
                    }
                    onOperation();
                });
                return;
            }
            if ((mState & GET_ROOM_IMAGE_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: we must delete the room (it must be possible even if we don't have the private peer!).
        //
        if (mRoom != null && (mWork & DELETE_ROOM) != 0) {
            if ((mState & DELETE_ROOM) == 0) {
                mState |= DELETE_ROOM;

                long requestId = newOperation(DELETE_ROOM);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.deleteContact: requestId=" + requestId + " contact=" + mRoom);
                }

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
        // Step 2: Get members list
        //
        if ((mState & GET_ROOM_MEMBERS) == 0) {
            mState |= GET_ROOM_MEMBERS;

            long requestId = newOperation(GET_ROOM_MEMBERS);
            mRoomRequestId = requestId;
            if (DEBUG) {
                Log.d(LOG_TAG, "roomListMembers: requestId=" + requestId + " mRoom:" + mRoom);
            }
            mTwinmeContext.roomListMembers(requestId, mRoom, RoomCommand.LIST_ALL);
            return;
        }
        if ((mState & GET_ROOM_MEMBERS_DONE) == 0) {
            return;
        }

        // Get room member name
        if (mCurrentRoomMemberId != null && (mWork & GET_ROOM_MEMBER) != 0) {
            if ((mState & GET_ROOM_MEMBER) == 0) {
                mState |= GET_ROOM_MEMBER;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwincodeOutboundService.getTwincode: twincodeOutboundId=" + mCurrentRoomMemberId);
                }
                mTwinmeContext.getTwincodeOutboundService().getTwincode(mCurrentRoomMemberId, TwincodeOutboundService.REFRESH_PERIOD, this::onGetRoomMember);
                return;
            }
            if ((mState & GET_ROOM_MEMBER_DONE) == 0) {
                return;
            }
        }

        // Get room member avatar
        if (mCurrentTwincodeOutbound != null && (mWork & GET_ROOM_MEMBER_AVATAR) != 0) {
            if ((mState & GET_ROOM_MEMBER_AVATAR) == 0) {
                mState |= GET_ROOM_MEMBER_AVATAR;

                ImageId avatarId = mCurrentTwincodeOutbound.getAvatarId();
                if (avatarId != null) {
                    Bitmap image = mTwinmeContext.getImageService().getImage(avatarId, ImageService.Kind.THUMBNAIL);
                    if (image != null) {
                        onGetRoomMemberAvatar(mCurrentTwincodeOutbound, image);
                    } else {
                        // Keep current member information, proceed with a default avatar and with the next member.
                        final TwincodeOutbound twincodeOutbound = mCurrentTwincodeOutbound;
                        nextRoomMemberAvatar();

                        // Then, try to get the image from the server in background.
                        mTwinmeContext.getImageService().getImageFromServer(avatarId, ImageService.Kind.THUMBNAIL, (ErrorCode errorCode, Bitmap avatar) -> {
                            if (avatar != null && mObserver != null) {
                                runOnUiThread(() -> {
                                    if (mObserver != null) {
                                        mObserver.onGetRoomMemberAvatar(twincodeOutbound, avatar);
                                    }
                                });
                            }
                        });
                    }
                    onOperation();
                } else {
                    nextRoomMemberAvatar();
                    onOperation();
                }
                return;
            }
            if ((mState & GET_ROOM_MEMBER_AVATAR_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }

    private void onGetRoom(@NonNull ErrorCode errorCode, @Nullable Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoom: room=" + room);
        }

        mRoom = room;

        mState |= GET_ROOM_DONE;
        if (room != null) {

            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, room.getId(), mRoomId);

            mAvatarId = room.getAvatarId();
            if (mAvatar == null) {
                mAvatar = getImage(room);
            }
            runOnGetContact(mObserver, room, mAvatar);
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

        mRoom = contact;

        // Check if the image was modified.
        if (mAvatarId != null && mAvatarId.equals(contact.getAvatarId())) {
            mAvatarId = contact.getAvatarId();
            mAvatar = getImage(contact);
            mState &= ~(GET_ROOM_THUMBNAIL_IMAGE | GET_ROOM_THUMBNAIL_IMAGE_DONE | GET_ROOM_IMAGE | GET_ROOM_IMAGE_DONE);
        }
        runOnUpdateContact(mObserver, contact, mAvatar);
        onOperation();
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        mState |= DELETE_ROOM_DONE;
        runOnDeleteContact(mObserver, contactId);
        onOperation();
    }

    private void onRoomCommandResult(@NonNull RoomCommandResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRoomCommandResult: result=" + result);
        }

        mState |= GET_ROOM_MEMBERS_DONE;

        if (result.getMembers() != null) {
            mWork |= GET_ROOM_MEMBER;
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);

            mMemberIds = result.getMembers();
            mRoomMembers = new ArrayList<>();
            mMemberCount = mMemberIds.size();
            nextRoomMember();
        }

        onOperation();
    }

    private void onGetRoomMember(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroupMember: member=" + twincodeOutbound);
        }

        mState |= GET_ROOM_MEMBER_DONE;

        if (twincodeOutbound != null) {
            mRoomMembers.add(twincodeOutbound);
        }
        nextRoomMember();
        onOperation();
    }

    private void nextRoomMember() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextRoomMember");
        }

        if (mMemberIds.isEmpty() || mRoomMembers.size() >= MAX_ROOM_MEMBER) {
            mState |= GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE;
            hideProgressIndicator();

            mCurrentRoomMemberId = null;
            final List<TwincodeOutbound> members = new ArrayList<>(mRoomMembers);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetRoomMembers(members, mMemberCount);
                }
            });
            mWork |= GET_ROOM_MEMBER_AVATAR;
            nextRoomMemberAvatar();
        } else {
            mCurrentRoomMemberId = mMemberIds.remove(0);
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);
        }
    }

    private void onGetRoomMemberAvatar(TwincodeOutbound twincodeOutbound, Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoomMemberAvatar: twincodeOutbound=" + twincodeOutbound + " avatar:" + avatar);
        }

        mState |= GET_ROOM_MEMBER_AVATAR_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetRoomMemberAvatar(twincodeOutbound, avatar);
            }
        });

        nextRoomMemberAvatar();
    }


    private void nextRoomMemberAvatar() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextRoomMemberAvatar");
        }

        if (mRoomMembers.isEmpty()) {
            mState |= GET_ROOM_MEMBER_AVATAR | GET_ROOM_MEMBER_AVATAR_DONE;
            mCurrentTwincodeOutbound = null;
        } else {
            mCurrentTwincodeOutbound = mRoomMembers.remove(0);
            mState &= ~(GET_ROOM_MEMBER_AVATAR | GET_ROOM_MEMBER_AVATAR_DONE);
        }
    }

    @Override
    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        if (errorCode == ErrorCode.ITEM_NOT_FOUND && operationId == DELETE_ROOM) {
            mState |= DELETE_ROOM_DONE;
            runOnDeleteContact(mObserver, mRoomId);
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
