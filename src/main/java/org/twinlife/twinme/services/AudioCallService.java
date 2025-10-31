/*
 *  Copyright (c) 2018-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class AudioCallService extends AbstractTwinmeService {
    private static final String LOG_TAG = "AudioCallService";
    private static final boolean DEBUG = false;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;

    public interface Observer extends AbstractTwinmeService.Observer {

        void onTwinlifeOnline();

        void onGetOriginator(@NonNull Originator originator, @Nullable Bitmap avatar);

        void onGetIdentityAvatar(@NonNull Originator originator, @Nullable Bitmap avatar);

        void onGetOriginatorNotFound();

        void onUpdateOriginator(@NonNull Originator originator, @Nullable Bitmap avatar);

        void onUpdateIdentityAvatar(@NonNull Originator originator, @Nullable Bitmap avatar);

        void onError(ErrorCode errorCode, String errorParameter);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onUpdateContact(long requestId, @NonNull final Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            AudioCallService.this.onUpdateContact(contact);
        }
    }

    @Nullable
    private AudioCallService.Observer mObserver;
    private final UUID mOriginatorId;
    private final UUID mGroupId;

    private int mState = 0;

    public AudioCallService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                            @NonNull Observer observer, @NonNull UUID originatorId, @Nullable UUID groupId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "AudioCallService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " observer=" + observer + " originatorId=" + originatorId);
        }

        mObserver = observer;

        mOriginatorId = originatorId;
        mTwinmeContext.assertNotNull(ServiceAssertPoint.NULL_SUBJECT, originatorId, 84);

        mGroupId = groupId;

        mTwinmeContextObserver = new TwinmeContextObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public boolean isConnected() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isConnected");
        }

        return mTwinmeContext.isConnected();
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    //
    // Private methods
    //

    @Override
    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onTwinlifeOnline();
            }
        });
        super.onTwinlifeOnline();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1
        //

        if (mOriginatorId != null) {
            if ((mState & GET_CONTACT) == 0) {
                mState |= GET_CONTACT;

                mTwinmeContext.getOriginator(mOriginatorId, mGroupId, this::onGetOriginator);
                return;
            }
            if ((mState & GET_CONTACT_DONE) == 0) {
                return;
            }
        }
        hideProgressIndicator();

        //
        // Last Step
        //
    }

    private void onGetOriginator(@NonNull ErrorCode errorCode, @Nullable Originator originator) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetOriginator: originator=" + originator);
        }

        mState |= GET_CONTACT_DONE;

        if (originator != null) {
            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, originator.getId(), mOriginatorId);

            final ImageService imageService = mTwinmeContext.getImageService();
            final ImageId avatarId = originator.getAvatarId();
            final Bitmap originatorAvatar;
            if (avatarId != null) {
                originatorAvatar = imageService.getImage(avatarId, ImageService.Kind.THUMBNAIL);
            } else {
                originatorAvatar = null;
            }

            final ImageId identityAvatarId = originator.getIdentityAvatarId();
            final Bitmap identityAvatar;
            if (identityAvatarId != null) {
                identityAvatar = imageService.getImage(identityAvatarId, ImageService.Kind.THUMBNAIL);
            } else {
                identityAvatar = null;
            }

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetOriginator(originator, originatorAvatar);
                    mObserver.onGetIdentityAvatar(originator, identityAvatar);
                }
            });

            if (avatarId != null) {
                Bitmap image = imageService.getImage(avatarId, ImageService.Kind.NORMAL);
                if (image != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onUpdateOriginator(originator, image);
                        }
                    });
                }
            }

            if (identityAvatarId != null) {
                Bitmap image = imageService.getImage(identityAvatarId, ImageService.Kind.NORMAL);
                if (image != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onUpdateIdentityAvatar(originator, image);
                        }
                    });
                }

            }
        }
        onOperation();
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        if (!contact.getId().equals(mOriginatorId)) {

            return;
        }

        final ImageService imageService = mTwinmeContext.getImageService();
        final ImageId avatarId = contact.getAvatarId();
        final Bitmap avatar;
        if (avatarId != null) {
            avatar = imageService.getImage(avatarId, ImageService.Kind.THUMBNAIL);
        } else {
            avatar = null;
        }
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateOriginator(contact, avatar);
            }
        });

        if (avatarId != null) {
            Bitmap image = mTwinmeContext.getImageService().getImage(avatarId, ImageService.Kind.NORMAL);
            if (image != null) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onUpdateOriginator(contact, image);
                    }
                });
            }
        }
        onOperation();
    }

    protected void onError(int operationId, BaseService.ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == BaseService.ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        if (operationId == GET_CONTACT) {

            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onGetOriginatorNotFound();
                    }
                });

                return;
            }
        }
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onError(errorCode, errorParameter);
            }
        });
    }
}
