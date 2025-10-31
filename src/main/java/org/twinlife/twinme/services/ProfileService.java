/*
 *  Copyright (c) 2017-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
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
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

public class ProfileService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ProfileService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_IDENTITY_AVATAR = 1 << 2;
    private static final int GET_IDENTITY_AVATAR_DONE = 1 << 3;

    public interface Observer extends AbstractTwinmeService.Observer, SpaceObserver {

        void onGetProfileNotFound();

        void onGetProfileAvatar(@NonNull Bitmap avatar);

        void onUpdateProfile(@NonNull Profile profile);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onUpdateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateProfile: requestId=" + requestId + " profile=" + profile);
            }

            ProfileService.this.onUpdateProfile(profile);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            ProfileService.this.onSetCurrentSpace(space);
        }

        @Override
        public void onUpdateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateSpace: requestId=" + requestId + " space=" + space);
            }

            ProfileService.this.onGetCurrentSpace(ErrorCode.SUCCESS, space);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private ImageId mAvatarId;

    public ProfileService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ProfileService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
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

            mTwinmeContext.getCurrentSpace(this::onGetCurrentSpace);
            return;
        }

        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2: get the profile normal image if we can.
        //
        if (mAvatarId != null) {
            if ((mState & GET_IDENTITY_AVATAR) == 0) {
                mState |= GET_IDENTITY_AVATAR;

                Bitmap image = mTwinmeContext.getImageService().getImage(mAvatarId, ImageService.Kind.NORMAL);
                mState |= GET_IDENTITY_AVATAR_DONE;
                if (image != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onGetProfileAvatar(image);
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

    private void onGetCurrentSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;

        if (space != null && space.getProfile() != null) {
            Profile profile = space.getProfile();
            ImageId avatarId = profile.getAvatarId();
            if (mAvatarId != avatarId) {
                mAvatarId = avatarId;
                mState &= ~(GET_IDENTITY_AVATAR | GET_IDENTITY_AVATAR_DONE);
            }
            runOnGetSpace(mObserver, space, null);
        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetProfileNotFound();
                }
            });
        }
        onOperation();
    }

    private void onUpdateProfile(@NonNull Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateProfile: profile=" + profile);
        }

        // Check if profile avatar is changed to get the new image.
        ImageId avatarId = profile.getAvatarId();
        if (mAvatarId == null || !mAvatarId.equals(avatarId)) {
            mAvatarId = avatarId;
            mState &= ~(GET_IDENTITY_AVATAR | GET_IDENTITY_AVATAR_DONE);
        }
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateProfile(profile);
            }
        });
        onOperation();
    }
}
