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
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.models.SpaceSettings;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;

public class CreateProfileService extends AbstractTwinmeService {
    private static final String LOG_TAG = "CreateProfileService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int CREATE_SPACE = 1 << 2;
    private static final int CREATE_SPACE_DONE = 1 << 3;
    private static final int CREATE_PROFILE = 1 << 4;
    private static final int CREATE_PROFILE_DONE = 1 << 5;
    private static final int SET_CURRENT_SPACE = 1 << 6;
    private static final int SET_LEVEL = 1 << 7;
    private static final int CREATE_DEFAULT_SPACE = 1 << 8;
    private static final int CREATE_DEFAULT_SPACE_DONE = 1 << 9;

    public interface Observer extends AbstractTwinmeService.Observer {

        void onCreateSpace(@NonNull Space space);

        void onCreateProfile(@NonNull Profile profile);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        public void onCreateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateProfile: requestId=" + requestId + " profile=" + profile);
            }

            if (getOperation(requestId) != null) {
                CreateProfileService.this.onCreateProfile(profile);
            }
        }

        public void onCreateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateSpace: requestId=" + requestId + " space=" + space);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                switch (operationId) {
                    case CREATE_DEFAULT_SPACE:
                        CreateProfileService.this.onCreateDefaultSpace(space);
                        break;

                    case CREATE_SPACE:
                        CreateProfileService.this.onCreateSpace(space);
                        break;
                }
            }
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            finishOperation(requestId);

            CreateProfileService.this.onSetCurrentSpace(space);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    private Space mSpace;
    private String mSpaceName;
    private String mName;
    private String mDescription;
    private Bitmap mAvatar;
    private File mAvatarFile;

    public CreateProfileService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "CreateProfileService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
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

    public void createProfile(@NonNull String spaceName, @NonNull String name, @Nullable String descriptionProfile, @NonNull Bitmap avatar, @Nullable File avatarFile) {
        createProfile(spaceName, name, descriptionProfile, avatar, avatarFile, false);
    }

    public void createProfile(@NonNull String spaceName, @NonNull String name, @Nullable String descriptionProfile, @NonNull Bitmap avatar, @Nullable File avatarFile, boolean createSpace) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createProfile: name=" + name + " descriptionProfile=" + descriptionProfile + " avatar=" + avatar + " avatarFile=" + avatarFile + " createSpace=" + createSpace);
        }

        mSpaceName = spaceName;
        mName = name;
        mDescription = descriptionProfile;
        mAvatar = avatar;
        mAvatarFile = avatarFile;
        mWork |= CREATE_PROFILE;
        if (mSpace == null) {
            mWork |= CREATE_DEFAULT_SPACE;
        } else if (createSpace) {
            mWork |= CREATE_SPACE;
        }
        showProgressIndicator();
        startOperation();
    }

    public void setLevel(@NonNull String level) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setLevel level=" + level);
        }

        if (!level.isEmpty()) {
            mTwinmeContext.setLevel(newOperation(SET_LEVEL), level);
        }
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
        // Step 1: get the current space.
        //

        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                mSpace = space;
                mState |= GET_CURRENT_SPACE_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        if ((mWork & CREATE_SPACE) != 0) {

            if ((mState & CREATE_SPACE) == 0) {
                mState |= CREATE_SPACE;

                long requestId = newOperation(CREATE_SPACE);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.createSpace: requestId=" + requestId + " name=" + mName + " avatar=" + mAvatar);
                }
                SpaceSettings spaceSettings = mTwinmeContext.getDefaultSpaceSettings();
                spaceSettings.setName(mSpaceName);
                mTwinmeContext.createSpace(requestId, spaceSettings, null, null);
                return;
            }
            if ((mState & CREATE_SPACE_DONE) == 0) {
                return;
            }
        }

        if ((mWork & CREATE_DEFAULT_SPACE) != 0) {

            if ((mState & CREATE_DEFAULT_SPACE) == 0) {
                mState |= CREATE_DEFAULT_SPACE;

                long requestId = newOperation(CREATE_DEFAULT_SPACE);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.createSpace: requestId=" + requestId);
                }
                SpaceSettings spaceSettings = mTwinmeContext.getDefaultSpaceSettings();
                spaceSettings.setName(mSpaceName);

                mTwinmeContext.createSpace(requestId, spaceSettings, null, null);
                return;
            }
            if ((mState & CREATE_DEFAULT_SPACE_DONE) == 0) {
                return;
            }
        }

        if ((mWork & CREATE_PROFILE) != 0) {

            if ((mState & CREATE_PROFILE) == 0) {
                mState |= CREATE_PROFILE;

                long requestId = newOperation(CREATE_PROFILE);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.createProfile: requestId=" + requestId + " name=" + mName + " avatar=" + mAvatar);
                }

                mTwinmeContext.createProfile(requestId, mName, mAvatar, mAvatarFile, mDescription, null, mSpace);
                return;
            }
            if ((mState & CREATE_PROFILE_DONE) == 0) {
                return;
            }
        }

        hideProgressIndicator();
        super.onOperation();
    }

    private void onCreateSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateSpace space=" + space);
        }

        mState |= CREATE_SPACE_DONE;

        if (mSpace == null) {
            mTwinmeContext.setDefaultSpace(space);
        }

        mSpace = space;

        long requestId = newOperation(SET_CURRENT_SPACE);
        mTwinmeContext.setCurrentSpace(requestId, space);

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateSpace(space);
            }
        });
        onOperation();
    }

    private void onCreateDefaultSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateDefaultSpace space=" + space);
        }

        mState |= CREATE_DEFAULT_SPACE_DONE;

        mSpace = space;
        mTwinmeContext.setDefaultSpace(space);

        long requestId = newOperation(SET_CURRENT_SPACE);
        mTwinmeContext.setCurrentSpace(requestId, space);

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateSpace(space);
            }
        });
        onOperation();
    }

    private void onCreateProfile(@NonNull Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateProfile profile=" + profile);
        }

        mState |= CREATE_PROFILE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateProfile(profile);
            }
        });
        onOperation();
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        mSpace = space;
        super.onSetCurrentSpace(space);
        onOperation();
    }
}
