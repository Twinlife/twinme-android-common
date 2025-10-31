/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.TrustMethod;
import org.twinlife.twinlife.TwincodeInbound;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class ShareProfileService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ShareProfileService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int CHANGE_PROFILE_TWINCODE = 1 << 2;
    private static final int CHANGE_PROFILE_TWINCODE_DONE = 1 << 3;
    private static final int GET_ROOM = 1 << 4;
    private static final int GET_ROOM_DONE = 1 << 5;
    private static final int GET_PROFILE = 1 << 6;
    private static final int GET_PROFILE_DONE = 1 << 7;
    private static final int GET_TWINCODE = 1 << 8;
    private static final int GET_TWINCODE_DONE = 1 << 9;
    private static final int CREATE_PRIVATE_KEY = 1 << 10;
    private static final int CREATE_PRIVATE_KEY_DONE = 1 << 11;
    private static final int GET_INVITATION_LINK = 1 << 12;
    private static final int GET_INVITATION_LINK_DONE = 1 << 13;

    public interface Observer extends AbstractTwinmeService.Observer, AbstractTwinmeService.ContactObserver {

        void onGetDefaultProfile(@NonNull Profile profile);

        void onGetDefaultProfileNotFound();

        void onGetTwincodeURI(@NonNull TwincodeURI uri);

        void onCreateContact(@NonNull Contact contact);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            ShareProfileService.this.onCreateContact(contact);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    @Nullable
    private Profile mProfile;
    @Nullable
    private Contact mRoom;
    @Nullable
    private TwincodeOutbound mTwincodeOutbound;
    @Nullable
    private TwincodeInbound mTwincodeInbound;
    private UUID mProfileId;
    private UUID mRoomId;
    private Runnable mOnChangeTwincode;

    public ShareProfileService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ShareProfileService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
        showProgressIndicator();
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void getProfile(@NonNull UUID profileId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getProfile: profileId=" + profileId);
        }

        mWork |= GET_PROFILE;
        mState &= ~(GET_PROFILE | GET_PROFILE_DONE);
        mProfileId = profileId;
        mTwincodeOutbound = null;
        showProgressIndicator();
        startOperation();
    }

    public void changeProfileTwincode(Runnable onChanged) {
        if (DEBUG) {
            Log.d(LOG_TAG, "changeProfileTwincode");
        }

        mOnChangeTwincode = onChanged;
        mWork = CHANGE_PROFILE_TWINCODE;
        mTwincodeOutbound = null;
        mState &= ~(CHANGE_PROFILE_TWINCODE | CHANGE_PROFILE_TWINCODE_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void getRoom(UUID roomId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getRoom roomId=" + roomId);
        }

        mRoomId = roomId;
        mTwincodeOutbound = null;
        mWork = GET_ROOM;
        mState &= ~(GET_ROOM | GET_ROOM_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void verifyAuthenticateURI(@NonNull Uri uri, @NonNull TwinmeContext.ConsumerWithError<Contact> complete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "verifyAuthenticateURI: uri=" + uri);
        }

        parseURI(uri, (ErrorCode errorCode, TwincodeURI twincodeURI) -> {
            if (errorCode != ErrorCode.SUCCESS || twincodeURI == null) {
                runOnUiThread(() -> complete.onGet(errorCode, null));
            } else {
                mTwinmeContext.verifyContact(twincodeURI, TrustMethod.QR_CODE,
                        (ErrorCode lErrorCode, Contact contact) -> runOnUiThread(() -> complete.onGet(lErrorCode, contact)));
            }
        });
    }

    //
    // Private methods
    //

    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;

            if ((mState & GET_TWINCODE) != 0 && (mState & GET_TWINCODE_DONE) == 0) {
                mState &= ~GET_TWINCODE;
            }
            if ((mState & CREATE_PRIVATE_KEY) != 0 && (mState & CREATE_PRIVATE_KEY_DONE) == 0) {
                mState &= ~CREATE_PRIVATE_KEY;
            }
        }
    }

    @Override
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: get the current space.
        //

        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            long requestId = newOperation(GET_CURRENT_SPACE);
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.getCurrentSpace: requestId=" + requestId);
            }

            mTwinmeContext.getCurrentSpace(this::onGetCurrentSpace);
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        // We must get the profile.
        if ((mWork & GET_PROFILE) != 0) {
            if ((mState & GET_PROFILE) == 0) {
                mState |= GET_PROFILE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.getProfile: profileId=" + mProfileId);
                }
                mTwinmeContext.getProfile(mProfileId, this::onGetProfile);
                return;
            }
            if ((mState & GET_PROFILE_DONE) == 0) {
                return;
            }
        }

        if (mRoomId != null && (mWork & GET_ROOM) != 0) {
            if ((mState & GET_ROOM) == 0) {
                mState |= GET_ROOM;

                mTwinmeContext.getContact(mRoomId, this::onGetRoom);
                return;
            }
            if ((mState & GET_ROOM_DONE) == 0) {
                return;
            }
        }

        if (mRoom != null && (mWork & GET_TWINCODE) != 0 && mRoom.getPublicPeerTwincodeOutboundId() != null) {
            if ((mState & GET_TWINCODE) == 0) {
                mState |= GET_TWINCODE;

                mTwinmeContext.getTwincodeOutboundService().getTwincode(mRoom.getPublicPeerTwincodeOutboundId(),
                        TwincodeOutboundService.REFRESH_PERIOD, this::onGetTwincode);
                return;
            }
            if ((mState & GET_TWINCODE_DONE) == 0) {
                return;
            }
        }

        //
        // We must change the current profile twincode.
        //
        if (mProfile != null && (mWork & CHANGE_PROFILE_TWINCODE) != 0) {
            if ((mState & CHANGE_PROFILE_TWINCODE) == 0) {
                mState |= CHANGE_PROFILE_TWINCODE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.changeProfileTwincode: profile=" + mProfile);
                }
                mTwinmeContext.changeProfileTwincode(mProfile, this::onChangeProfileTwincode);
                return;
            }

            if ((mState & CHANGE_PROFILE_TWINCODE_DONE) == 0) {
                return;
            }
        }

        if (mTwincodeInbound != null && (mWork & CREATE_PRIVATE_KEY) != 0) {
            if ((mState & CREATE_PRIVATE_KEY) == 0) {
                mState |= CREATE_PRIVATE_KEY;
                mTwinmeContext.getTwincodeOutboundService().createPrivateKey(mTwincodeInbound,
                        this::onCreatePrivateKey);
                return;
            }

            if ((mState & CREATE_PRIVATE_KEY_DONE) == 0) {
                return;
            }
        }

        if (mTwincodeOutbound != null && (mWork & GET_INVITATION_LINK) != 0) {
            if ((mState & GET_INVITATION_LINK) == 0) {
                mState |= GET_INVITATION_LINK;
                mTwinmeContext.getTwincodeOutboundService().createURI(TwincodeURI.Kind.Invitation, mTwincodeOutbound,
                        this::onCreateURI);
                return;
            }

            if ((mState & GET_INVITATION_LINK_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step: everything done, we can hide the progress indicator.
        //

        hideProgressIndicator();
    }

    private void onCreateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateContact: contact=" + contact);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateContact(contact);
            }
        });
    }

    private void onGetCurrentSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;
        onGetProfile(errorCode, space == null ? null : space.getProfile());
        onOperation();
    }

    private void onGetProfile(@NonNull ErrorCode errorCode, @Nullable Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetProfile: profile=" + profile);
        }

        mState |= GET_PROFILE_DONE;
        mProfile = profile;
        if (profile != null) {
            mTwincodeOutbound = profile.getTwincodeOutbound();
            if (mTwincodeOutbound != null && !mTwincodeOutbound.isSigned()) {
                mTwincodeInbound = profile.getTwincodeInbound();
                mWork |= CREATE_PRIVATE_KEY;
                mState &= ~(CREATE_PRIVATE_KEY | CREATE_PRIVATE_KEY_DONE);
            }
            mWork |= GET_INVITATION_LINK;
            mState &= ~(GET_INVITATION_LINK | GET_INVITATION_LINK_DONE);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDefaultProfile(profile);
                }
            });
        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDefaultProfileNotFound();
                }
            });
        }
        onOperation();
    }

    private void onChangeProfileTwincode(@NonNull ErrorCode errorCode, @Nullable Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onChangeProfileTwincode profile=" + profile);
        }

        mState |= CHANGE_PROFILE_TWINCODE_DONE;
        if (errorCode != ErrorCode.SUCCESS || profile == null) {
            onError(CHANGE_PROFILE_TWINCODE, errorCode, null);
        } else {
            mWork |= GET_INVITATION_LINK;
            mState &= ~(GET_INVITATION_LINK | GET_INVITATION_LINK_DONE);
            mTwincodeOutbound = profile.getTwincodeOutbound();
            runOnUiThread(() -> {
                if (mOnChangeTwincode != null) {
                    mOnChangeTwincode.run();
                    mOnChangeTwincode = null;
                }
            });
        }
        onOperation();
    }

    private void onGetRoom(@NonNull ErrorCode errorCode, @Nullable Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoom: room=" + room);
        }

        mState |= GET_ROOM_DONE;
        mRoom = room;
        if (room != null) {
            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, room.getId(), mRoomId);
            if (mRoom.getPublicPeerTwincodeOutboundId() != null) {
                mWork |= GET_TWINCODE;
                mState &= ~(GET_TWINCODE | GET_TWINCODE_DONE);
            }
            runOnGetContact(mObserver, room, null);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_ROOM, errorCode, null);
        }
        onOperation();
    }

    private void onGetTwincode(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetTwincode: errorCode=" + errorCode + " twincodeOutbound=" + twincodeOutbound);
        }

        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;
            return;
        }

        mState |= GET_TWINCODE_DONE;
        mTwincodeOutbound = twincodeOutbound;
        if (twincodeOutbound != null) {
            mWork |= GET_INVITATION_LINK;
            mState &= ~(GET_INVITATION_LINK | GET_INVITATION_LINK_DONE);
        }
        onOperation();
    }

    private void onCreatePrivateKey(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreatePrivateKey: errorCode=" + errorCode + " twincodeOutbound=" + twincodeOutbound);
        }

        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;
            return;
        }

        mState |= CREATE_PRIVATE_KEY_DONE;
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

    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        if (errorCode == ErrorCode.ITEM_NOT_FOUND && operationId == CHANGE_PROFILE_TWINCODE) {
            // It can happen that a profile is removed while an changeProfileTwincode() operation was made.
            mState |= CHANGE_PROFILE_TWINCODE;

            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
