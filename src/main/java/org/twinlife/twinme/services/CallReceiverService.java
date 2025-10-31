/*
 *  Copyright (c) 2023-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Capabilities;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class CallReceiverService extends AbstractTwinmeService {
    private static final String LOG_TAG = "CallReceiverService";
    private static final boolean DEBUG = false;
    private static final int CREATE_CALL_RECEIVER = 1;
    private static final int CREATE_CALL_RECEIVER_DONE = 1 << 1;
    private static final int GET_CALL_RECEIVER = 1 << 2;
    private static final int GET_CALL_RECEIVER_DONE = 1 << 3;
    private static final int GET_CALL_RECEIVERS = 1 << 4;
    private static final int GET_CALL_RECEIVERS_DONE = 1 << 5;
    private static final int DELETE_CALL_RECEIVER = 1 << 6;
    private static final int DELETE_CALL_RECEIVER_DONE = 1 << 7;
    private static final int GET_SPACE = 1 << 8;
    private static final int GET_SPACE_DONE = 1 << 9;
    private static final int UPDATE_CALL_RECEIVER = 1 << 10;
    private static final int UPDATE_CALL_RECEIVER_DONE = 1 << 11;
    private static final int CHANGE_CALL_RECEIVER_TWINCODE = 1 << 12;
    private static final int CHANGE_CALL_RECEIVER_TWINCODE_DONE = 1 << 13;
    private static final int GET_AVATAR = 1 << 14;
    private static final int GET_AVATAR_DONE = 1 << 15;
    private static final int GET_IDENTITY_AVATAR = 1 << 16;
    private static final int GET_IDENTITY_AVATAR_DONE = 1 << 17;
    private static final int GET_INVITATION_LINK = 1 << 18;
    private static final int GET_INVITATION_LINK_DONE = 1 << 19;

    public interface Observer extends AbstractTwinmeService.Observer {

        default void onGetSpace(Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onGetSpace: space=" + space);
            }
        }

        default void onGetSpaceNotFound() {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onGetSpaceNotFound");
            }
        }

        default void onCreateCallReceiver(@NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onCreateCallReceiver: callReceiver=" + callReceiver);
            }
        }

        default void onGetCallReceiver(@Nullable CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onGetCallReceiver: callReceiver=" + callReceiver);
            }
        }

        default void onGetCallReceivers(@NonNull List<CallReceiver> callReceivers) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onGetCallReceivers: callReceivers.size=" + callReceivers.size());
            }
        }

        default void onDeleteCallReceiver(@NonNull UUID callReceiverId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onDeleteCallReceiver: callReceiverId=" + callReceiverId);
            }
        }

        default void onUpdateCallReceiver(@NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onUpdateCallReceiver: callReceiver=" + callReceiver);
            }
        }

        default void onChangeCallReceiverTwincode(@NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onUpdateCallReceiver: callReceiver=" + callReceiver);
            }
        }

        default void onUpdateCallReceiverIdentityAvatar(@NonNull Bitmap avatar) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onUpdateCallReceiverIdentityAvatar: avatar=" + avatar);
            }
        }

        default void onUpdateCallReceiverAvatar(@NonNull Bitmap avatar) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onUpdateCallReceiverAvatar: avatar=" + avatar);
            }
        }

        default void onGetTwincodeURI(@NonNull TwincodeURI uri) {
            if (DEBUG) {
                Log.d(LOG_TAG, "default Observer.onGetTwincodeURI: uri=" + uri);
            }
        }
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onDeleteCallReceiver(long requestId, @NonNull UUID callReceiverId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteCallReceiver: requestId=" + requestId + " callReceiverId=" + callReceiverId);
            }

            CallReceiverService.this.onDeleteCallReceiver(callReceiverId);
        }

        @Override
        public void onUpdateCallReceiver(long requestId, @NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateCallReceiver: requestId=" + requestId + " callReceiver=" + callReceiver);
            }

            CallReceiverService.this.onUpdateCallReceiver(callReceiver);
        }

        @Override
        public void onChangeCallReceiverTwincode(long requestId, @NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onChangeCallReceiverTwincode: requestId=" + requestId + " callReceiver=" + callReceiver);
            }

            CallReceiverService.this.onChangeCallReceiverTwincode(callReceiver);
        }

        @Override
        public void onCreateCallReceiver(long requestId, @NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateCallReceiver: requestId=" + requestId + " callReceiver=" + callReceiver);
            }

            CallReceiverService.this.onCreateCallReceiver(callReceiver);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;


    //
    // CallReceiver creation attributes
    //
    @Nullable
    private String mName;
    @Nullable
    private String mDescription;
    @Nullable
    private String mIdentityName;
    @Nullable
    private String mIdentityDescription;
    @Nullable
    private Bitmap mAvatar;
    @Nullable
    private File mAvatarFile;
    @Nullable
    private Capabilities mCapabilities;
    @Nullable
    private Space mSpace;
    @Nullable
    private ImageId mAvatarId;
    @Nullable
    private ImageId mIdentityAvatarId;
    @Nullable
    private TwincodeOutbound mTwincodeOutbound;
    @Nullable
    private TwincodeURI.Kind mInvitationKind;

    //
    // get CallReceiver attributes
    //
    @Nullable
    private UUID mCallReceiverId;

    @Nullable
    private CallReceiver mCallReceiver;


    public CallReceiverService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "CallReceiverService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }


    /**
     * <p>
     * Create a new {@link CallReceiver}.
     * <p>
     * All parameters except for the space are optional. Null parameters will default to the values
     * found in the space's profile.
     * <p>
     * Once created the resulting CallReceiver will be made available
     * through the {@link Observer#onCreateCallReceiver(CallReceiver)} callback.
     *
     * @param space               Mandatory. The call receiver will be linked to the space's profile.
     * @param name                The label of the call receiver (device-local).
     * @param description         The description of the call receiver (device-local).
     * @param identityName        The name of the call receiver's twincodeOutbound.
     * @param identityDescription The description of the call receiver's twincodeOutbound.
     * @param avatar              The thumbnail of the call receiver's avatar.
     * @param avatarFile          The actual call receiver's avatar. Mandatory if avatar is not null.
     * @param capabilities        The call receiver's advertised capabilities.
     */
    public void createCallReceiver(@NonNull Space space, @Nullable String name, @Nullable String description, @Nullable String identityName, @Nullable String identityDescription, @Nullable Bitmap avatar, @Nullable File avatarFile, @Nullable Capabilities capabilities) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createCallReceiver: mAvatarFile= " + avatarFile);
        }

        mSpace = space;
        mName = name;
        mDescription = description;
        mIdentityName = identityName;
        mIdentityDescription = identityDescription;
        mAvatar = avatar;
        mAvatarFile = avatarFile;
        mCapabilities = capabilities;

        mWork |= CREATE_CALL_RECEIVER;
        mState &= ~(CREATE_CALL_RECEIVER | CREATE_CALL_RECEIVER_DONE);

        showProgressIndicator();
        startOperation();
    }

    /**
     * <p>
     * Find a {@link CallReceiver} by its DB ID.
     * <p>
     * Once found, the CallReceiver will be made available through the
     * {@link Observer#onGetCallReceiver(CallReceiver)} callback. If no CallReceiver was found,
     * the callback will be called with a null argument.
     *
     * @param callReceiverId the ID of the CallReceiver.
     */
    public void getCallReceiver(@NonNull UUID callReceiverId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCallReceiver: callReceiverId=" + callReceiverId);
        }

        mCallReceiverId = callReceiverId;

        mWork |= GET_CALL_RECEIVER;
        mState &= ~(GET_CALL_RECEIVER | GET_CALL_RECEIVER_DONE);

        startOperation();
    }

    /**
     * <p>
     * Retrieve all {@link CallReceiver}s.
     * <p>
     * Once retrieved from the DB (or the in-memory cache), the CallReceivers will be made available through the
     * {@link Observer#onGetCallReceivers(List)} callback.
     */
    public void getCallReceivers() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCallReceivers");
        }

        mWork |= GET_CALL_RECEIVERS;
        mState &= ~(GET_CALL_RECEIVERS | GET_CALL_RECEIVERS_DONE);

        startOperation();
    }

    /**
     * <p>
     * Delete a {@link CallReceiver}.
     * <p>
     * Once deleted the {@link Observer#onDeleteCallReceiver(UUID)} callback will be called with the CallReceiver's ID.
     * <p>
     * An alternative is to implement the {@link TwinmeContext.Observer#onDeleteCallReceiver(long, UUID)} callback to
     * monitor CallReceivers deletions.
     *
     * @param callReceiver the CallReceiver to delete
     */
    public void deleteCallReceiver(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteCallReceiver: callReceiver=" + callReceiver);
        }

        mCallReceiver = callReceiver;

        mWork |= DELETE_CALL_RECEIVER;
        mState &= ~(DELETE_CALL_RECEIVER | DELETE_CALL_RECEIVER_DONE);

        startOperation();
    }

    /**
     * <p>
     * Reset the {@link CallReceiver}'s Twincode, with the same attributes.
     * <p>
     * Once the twincode is reset, the {@link Observer#onChangeCallReceiverTwincode(CallReceiver)} callback
     * will be called with the updated CallReceiver.
     *
     * @param callReceiver The CallReceiver whose twincode will be reset.
     */
    public void changeCallReceiverTwincode(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "changeCallReceiverTwincode: callReceiver=" + callReceiver);
        }

        mCallReceiver = callReceiver;

        mWork |= CHANGE_CALL_RECEIVER_TWINCODE;
        mState &= ~(CHANGE_CALL_RECEIVER_TWINCODE | CHANGE_CALL_RECEIVER_TWINCODE_DONE);

        startOperation();
    }

    /**
     * <p>
     * Update a {@link CallReceiver}.
     * <p>
     * Apart from the CallReceiver itself and its name, all parameters are optional.
     * null parameters will be ignored and the current value will be kept.
     *
     * @param callReceiver The CallReceiver to update.
     * @param name         The label of the call receiver. Mandatory (the current name should be passed if it does not need to be changed).
     * @param description  The description of the call receiver (device-local).
     * @param identityName The name of the call receiver's twincodeOutbound.
     * @param identityDescription  The description of the call receiver's twincodeOutbound.
     * @param avatar       The thumbnail of the call receiver's avatar.
     * @param avatarFile   The actual call receiver's avatar. Mandatory if avatar is not null.
     * @param capabilities The call receiver's advertised capabilities.
     */
    public void updateCallReceiver(@NonNull CallReceiver callReceiver, @Nullable String name, @Nullable String description, @Nullable String identityName, @Nullable String identityDescription, @Nullable Bitmap avatar, @Nullable File avatarFile, @Nullable Capabilities capabilities) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateCallReceiver: callReceiver=");
        }

        mCallReceiver = callReceiver;
        mName = name;
        mDescription = description;
        mIdentityName = identityName;
        mIdentityDescription = identityDescription;
        mAvatar = avatar;
        mAvatarFile = avatarFile;
        mCapabilities = capabilities;

        mWork |= UPDATE_CALL_RECEIVER;
        mState &= ~(UPDATE_CALL_RECEIVER | UPDATE_CALL_RECEIVER_DONE);

        startOperation();
    }

    public void getLargeAvatar(@NonNull ImageId avatarId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getLargeAvatar: avatarId=" + avatarId);
        }

        mWork |= GET_AVATAR;
        mState &= ~(GET_AVATAR | GET_AVATAR_DONE);
        mAvatarId = avatarId;
        showProgressIndicator();
        startOperation();
    }

    public void getLargeIdentityAvatar(@NonNull ImageId avatarId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getLargeIdentityAvatar: avatarId=" + avatarId);
        }

        mWork |= GET_IDENTITY_AVATAR;
        mState &= ~(GET_IDENTITY_AVATAR | GET_IDENTITY_AVATAR_DONE);
        mIdentityAvatarId = avatarId;
        showProgressIndicator();
        startOperation();
    }

    //
    // Private methods
    //

    @Override
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        if ((mState & GET_SPACE) == 0) {
            mState |= GET_SPACE;

            long requestId = newOperation(GET_SPACE);
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.getCurrentSpace: requestId=" + requestId);
            }

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> runOnUiThread(() -> {
                finishOperation(requestId);
                if (mObserver != null) {
                    if (space != null) {
                        mSpace = space;
                        mObserver.onGetSpace(space);
                    } else {
                        mObserver.onGetSpaceNotFound();
                    }
                }
                mState |= GET_SPACE_DONE;
                onOperation();
            }));
            return;
        }
        if ((mState & GET_SPACE_DONE) == 0) {
            return;
        }

        //
        // Create a Call Receiver
        //

        if (mSpace != null && (mWork & CREATE_CALL_RECEIVER) != 0) {
            if ((mState & CREATE_CALL_RECEIVER) == 0) {
                mState |= CREATE_CALL_RECEIVER;

                long requestId = newOperation(CREATE_CALL_RECEIVER);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.createCallReceiver: requestId=" + requestId + " mAvatarFile=" + mAvatarFile);
                }

                mTwinmeContext.createCallReceiver(requestId, mSpace, mName, mDescription, mIdentityName, mIdentityDescription, mAvatar, mAvatarFile, mCapabilities, this::onCreateCallReceiver);
                return;
            }

            if ((mState & CREATE_CALL_RECEIVER_DONE) == 0) {
                return;
            }
        }

        //
        // Get a call receiver by ID
        //

        if (mCallReceiverId != null && (mWork & GET_CALL_RECEIVER) != 0) {
            if ((mState & GET_CALL_RECEIVER) == 0) {
                mState |= GET_CALL_RECEIVER;

                mTwinmeContext.getCallReceiver(mCallReceiverId, this::onGetCallReceiver);
                return;
            }

            if ((mState & GET_CALL_RECEIVER_DONE) == 0) {
                return;
            }
        }

        //
        // Get the call receiver URI.
        //
        if (mTwincodeOutbound != null && mInvitationKind != null && (mWork & GET_INVITATION_LINK) != 0) {
            if ((mState & GET_INVITATION_LINK) == 0) {
                mState |= GET_INVITATION_LINK;
                mTwinmeContext.getTwincodeOutboundService().createURI(mInvitationKind, mTwincodeOutbound,
                        this::onCreateURI);
                return;
            }

            if ((mState & GET_INVITATION_LINK_DONE) == 0) {
                return;
            }
        }

        //
        // Get all call receivers
        //

        if ((mWork & GET_CALL_RECEIVERS) != 0) {
            if ((mState & GET_CALL_RECEIVERS) == 0) {
                mState |= GET_CALL_RECEIVERS;

                long requestId = newOperation(GET_CALL_RECEIVERS);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.getCallReceivers: requestId=" + requestId);
                }
                // SCz fix mTwinmeContext.getCallReceivers(requestId, this::onGetCallReceivers);
                return;
            }

            if ((mState & GET_CALL_RECEIVERS_DONE) == 0) {
                return;
            }
        }

        //
        // Delete a call receiver
        //

        if (mCallReceiver != null && (mWork & DELETE_CALL_RECEIVER) != 0) {
            if ((mState & DELETE_CALL_RECEIVER) == 0) {
                mState |= DELETE_CALL_RECEIVER;

                long requestId = newOperation(DELETE_CALL_RECEIVER);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.deleteCallReceiver: requestId=" + requestId + " callReceiver=" + mCallReceiver);
                }
                mTwinmeContext.deleteCallReceiver(requestId, mCallReceiver);
                return;
            }

            if ((mState & DELETE_CALL_RECEIVER_DONE) == 0) {
                return;
            }
        }

        //
        // Update a call receiver
        //

        if (mCallReceiver != null && (mWork & UPDATE_CALL_RECEIVER) != 0) {
            if ((mState & UPDATE_CALL_RECEIVER) == 0) {
                mState |= UPDATE_CALL_RECEIVER;

                long requestId = newOperation(UPDATE_CALL_RECEIVER);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.updateCallReceiver: requestId=" + requestId);
                }
                mTwinmeContext.updateCallReceiver(requestId, mCallReceiver, mName, mDescription, mIdentityName, mIdentityDescription, mAvatar, mAvatarFile, mCapabilities);
                return;
            }

            if ((mState & UPDATE_CALL_RECEIVER_DONE) == 0) {
                return;
            }
        }

        //
        // Change call receiver's twincode
        //

        if (mCallReceiver != null && (mWork & CHANGE_CALL_RECEIVER_TWINCODE) != 0) {
            if ((mState & CHANGE_CALL_RECEIVER_TWINCODE) == 0) {
                mState |= CHANGE_CALL_RECEIVER_TWINCODE;

                long requestId = newOperation(CHANGE_CALL_RECEIVER_TWINCODE);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.changeCallReceiverTwincode: requestId=" + requestId + " callReceiver=" + mCallReceiver);
                }
                mTwinmeContext.changeCallReceiverTwincode(requestId, mCallReceiver);
                return;
            }

            if ((mState & CHANGE_CALL_RECEIVER_TWINCODE_DONE) == 0) {
                return;
            }
        }

        //
        // Change call receiver's twincode
        //

        if (mCallReceiver != null && (mWork & CHANGE_CALL_RECEIVER_TWINCODE) != 0) {
            if ((mState & CHANGE_CALL_RECEIVER_TWINCODE) == 0) {
                mState |= CHANGE_CALL_RECEIVER_TWINCODE;

                long requestId = newOperation(CHANGE_CALL_RECEIVER_TWINCODE);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.changeCallReceiverTwincode: requestId=" + requestId + " callReceiver=" + mCallReceiver);
                }
                mTwinmeContext.changeCallReceiverTwincode(requestId, mCallReceiver);
                return;
            }

            if ((mState & CHANGE_CALL_RECEIVER_TWINCODE_DONE) == 0) {
                return;
            }
        }

        //
        // Get the call receiver identity large image if we can.
        //
        if (mAvatarId != null && (mWork & GET_AVATAR) != 0) {
            if ((mState & GET_AVATAR) == 0) {
                mState |= GET_AVATAR;

                Bitmap image = mTwinmeContext.getImageService().getImage(mAvatarId, ImageService.Kind.NORMAL);
                mState |= GET_AVATAR_DONE;
                mAvatar = image;
                if (image != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onUpdateCallReceiverAvatar(image);
                        }
                    });
                }
                onOperation();
                return;
            }
            if ((mState & GET_AVATAR_DONE) == 0) {
                return;
            }
        }

        //
        // Get the call receiver identity large image if we can.
        //
        if (mIdentityAvatarId != null && (mWork & GET_IDENTITY_AVATAR) != 0) {
            if ((mState & GET_IDENTITY_AVATAR) == 0) {
                mState |= GET_IDENTITY_AVATAR;

                ImageService imageService = mTwinmeContext.getImageService();
                Bitmap image = imageService.getImage(mIdentityAvatarId, ImageService.Kind.NORMAL);
                mState |= GET_IDENTITY_AVATAR_DONE;
                if (image != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onUpdateCallReceiverIdentityAvatar(image);
                        }
                    });
                }
                onOperation();
                return;
            }
            if ((mState & GET_IDENTITY_AVATAR_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step: everything done, we can hide the progress indicator.
        //

        hideProgressIndicator();
    }

    private void onCreateCallReceiver(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateCallReceiver callReceiver=" + callReceiver);
        }

        mState |= CREATE_CALL_RECEIVER_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateCallReceiver(callReceiver);
            }
            onOperation();
        });
    }

    private void onGetCallReceiver(@NonNull ErrorCode errorCode, @Nullable CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCallReceiver callReceiver=" + callReceiver);
        }

        mState |= GET_CALL_RECEIVER_DONE;
        if (callReceiver != null) {
            mTwincodeOutbound = callReceiver.getTwincodeOutbound();
            mInvitationKind = callReceiver.isTransfer() ? TwincodeURI.Kind.Transfer : TwincodeURI.Kind.Call;
            mWork |= GET_INVITATION_LINK;
            mState &= ~(GET_INVITATION_LINK | GET_INVITATION_LINK_DONE);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetCallReceiver(callReceiver);
            }
        });
        onOperation();
    }

    private void onCreateURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateURI: errorCode=" + errorCode + " uri=" + uri);
        }

        mState |= GET_INVITATION_LINK_DONE;
        if (uri != null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetTwincodeURI(uri);
                }
            });
        }
        onOperation();
    }

    private void onGetCallReceivers(@NonNull List<CallReceiver> callReceivers) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCallReceiver callReceivers.size=" + callReceivers.size());
        }

        mState |= GET_CALL_RECEIVERS_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetCallReceivers(callReceivers);
            }
        });
        onOperation();
    }

    private void onDeleteCallReceiver(@NonNull UUID callReceiverId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteCallReceiver callReceiverId=" + callReceiverId);
        }

        mState |= DELETE_CALL_RECEIVER_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteCallReceiver(callReceiverId);
            }
        });
        onOperation();
    }

    private void onUpdateCallReceiver(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateCallReceiver callReceiver=" + callReceiver);
        }

        mState |= UPDATE_CALL_RECEIVER_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateCallReceiver(callReceiver);
            }
        });
        onOperation();
    }

    private void onChangeCallReceiverTwincode(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateCallReceiver callReceiver=" + callReceiver);
        }

        mState |= UPDATE_CALL_RECEIVER_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onChangeCallReceiverTwincode(callReceiver);
            }
        });

        mTwincodeOutbound = callReceiver.getTwincodeOutbound();
        mWork |= GET_INVITATION_LINK;
        mState &= ~(GET_INVITATION_LINK | GET_INVITATION_LINK_DONE);
        
        onOperation();
    }
}
