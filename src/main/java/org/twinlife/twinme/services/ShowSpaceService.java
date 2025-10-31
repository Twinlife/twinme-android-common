/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
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
import org.twinlife.twinme.models.SpaceSettings;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShowSpaceService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ShowSpaceService";
    private static final boolean DEBUG = false;

    private static final int GET_SPACE = 1;
    private static final int GET_SPACE_DONE = 1 << 1;
    private static final int GET_SPACE_IMAGE = 1 << 2;
    private static final int GET_SPACE_IMAGE_DONE = 1 << 3;
    private static final int UPDATE_SPACE = 1 << 4;
    private static final int UPDATE_SPACE_DONE = 1 << 5;
    private static final int GET_SPACES = 1 << 6;
    private static final int GET_SPACES_DONE = 1 << 7;

    public interface Observer extends AbstractTwinmeService.Observer, AbstractTwinmeService.SpaceObserver {

        void onUpdateSpace(Space space, Bitmap avatar);

        void onUpdateProfile(Profile profile);

        void onDeleteSpace(UUID spaceId);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            ShowSpaceService.this.onGetSpace(ErrorCode.SUCCESS, space);
        }

        @Override
        public void onCreateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateSpace: requestId=" + requestId + " space=" + space);
            }

            ShowSpaceService.this.onCreateSpace(space);
        }

        @Override
        public void onDeleteSpace(long requestId, @NonNull UUID spaceId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteSpace: requestId=" + requestId + " spaceId=" + spaceId);
            }

            ShowSpaceService.this.onDeleteSpace(spaceId);
        }

        @Override
        public void onUpdateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateSpace: requestId=" + requestId + " space=" + space);
            }

            ShowSpaceService.this.onUpdateSpace(space);
        }

        @Override
        public void onCreateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateProfile: requestId=" + requestId + " profile=" + profile);
            }

            ShowSpaceService.this.onUpdateProfile(profile);
        }

        @Override
        public void onUpdateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateProfile: requestId=" + requestId + " profile=" + profile);
            }

            ShowSpaceService.this.onUpdateProfile(profile);
        }
    }

    @Nullable
    private Observer mObserver;
    private UUID mSpaceId;
    private Space mSpace;
    private ImageId mAvatarId;
    private Bitmap mAvatar;
    private int mState;
    private boolean mCreateSpace;
    private final List<Space> mSpaces = new ArrayList<>();

    public ShowSpaceService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
                            @Nullable UUID spaceId, boolean createSpace) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ShowSpaceService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer + " createSpace=" + createSpace);
        }

        mObserver = observer;
        mSpaceId = spaceId;
        mCreateSpace = createSpace;

        mState &= ~(GET_SPACES | GET_SPACES_DONE);

        if (mSpaceId == null && !createSpace) {
            mState &= ~(GET_SPACE | GET_SPACE_DONE);
        }

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

    public void getSpace(UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: spaceId=" + spaceId);
        }

        mSpaceId = spaceId;
        mCreateSpace = false;
        mState &= ~(GET_SPACE | GET_SPACE_DONE);

        startOperation();
    }

    public void updateSpace(Space space, SpaceSettings spaceSettings) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateSpace: space= " + space + " spaceSettings= " + spaceSettings);
        }

        long requestId = newOperation(UPDATE_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "updateSpace: requestId=" + requestId + " spaceSettings= " + spaceSettings + "space= " + space + " spaceSettings= " + spaceSettings);
        }
        showProgressIndicator();

        mTwinmeContext.updateSpace(requestId, space, spaceSettings, null, null);
    }

    public int numberSpaces(boolean countSecretSpace) {
        if (DEBUG) {
            Log.d(LOG_TAG, "numberSpaces: " + countSecretSpace);
        }

        if (countSecretSpace) {
            return mSpaces.size();
        }

        int count = 0;
        for (Space space : mSpaces) {
            if (!space.getSpaceSettings().isSecret()) {
                count++;
            }
        }

        return count;
    }

    public boolean updateDefaultSpace(Space oldDefaultSpace) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDefaultSpace : " + oldDefaultSpace);
        }

        for (Space space : mSpaces) {
            if (!space.getSpaceSettings().isSecret() && !space.getId().equals(oldDefaultSpace.getId())) {
                mTwinmeContext.setDefaultSpace(space);
                return true;
            }
        }

        return false;
    }

    //
    // Private methods
    //

    private void onGetSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetSpace: space=" + space);
        }

        mState |= GET_SPACE_DONE;
        if (space != null) {
            mSpaceId = space.getId();
            mSpace = space;
            onUpdateImage();
            runOnGetSpace(mObserver, space, mAvatar);
            if (mAvatarId != null) {
                mState &= ~(GET_SPACE_IMAGE | GET_SPACE_IMAGE_DONE);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetSpace(mObserver, null, null);

        } else {
            onError(GET_SPACE, errorCode, null);
        }
        onOperation();
    }

    private void onUpdateImage() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateImage: space=" + mSpace);
        }

        UUID avatarId = mSpace.getSpaceAvatarId();
        if (avatarId != null) {
            ImageService imageService = mTwinmeContext.getImageService();
            mAvatarId = imageService.getImageId(avatarId);
            if (mAvatarId != null) {
                mAvatar = getSpaceImage(mSpace);
                mState &= ~(GET_SPACE_IMAGE | GET_SPACE_IMAGE_DONE);
            }
        } else {
            mAvatar = null;
            mAvatarId = null;
        }
    }

    private void onCreateSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateSpace: space=" + space);
        }

        mSpaces.add(space);

        if (mCreateSpace) {
            mSpace = space;
            mSpaceId = mSpace.getId();
            onUpdateImage();

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onUpdateSpace(space, mAvatar);
                }
            });

            onOperation();
        }
    }

    private void onUpdateSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateSpace: space=" + space);
        }

        for (Space lSpace : mSpaces) {
            if (lSpace.getId().equals(space.getId())) {
                mSpaces.remove(lSpace);
                mSpaces.add(space);
                break;
            }
        }

        if (!space.getId().equals(mSpaceId)) {

            return;
        }

        mState |= UPDATE_SPACE_DONE;
        mSpace = space;
        onUpdateImage();
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateSpace(space, mAvatar);
            }
        });

        onOperation();
    }

    private void onUpdateProfile(@NonNull Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateProfile: profile=" + profile);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateProfile(profile);
            }
        });
    }

    private void onDeleteSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteSpace: spaceId=" + spaceId);
        }

        for (Space space : mSpaces) {
            if (space.getId().equals(spaceId)) {
                mSpaces.remove(space);
                break;
            }
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteSpace(spaceId);
            }
        });
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
        // Step 1: get spaces.
        //

        if ((mState & GET_SPACES) == 0) {
            mState |= GET_SPACES;

            mTwinmeContext.findSpaces((Space space) -> true, (ErrorCode errorCode, List<Space> spaces) -> {
                mSpaces.clear();
                if (spaces != null) {
                    mSpaces.addAll(spaces);
                }
                mState |= GET_SPACES_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_SPACES_DONE) == 0) {
            return;
        }

        //
        // Step 2: Get the current space.
        //
        if (!mCreateSpace) {
            if ((mState & GET_SPACE) == 0) {
                mState |= GET_SPACE;

                if (mSpaceId != null) {
                    mTwinmeContext.getSpace(mSpaceId, this::onGetSpace);
                } else {
                    mTwinmeContext.getCurrentSpace(this::onGetSpace);
                }
                return;
            }
            if ((mState & GET_SPACE_DONE) == 0) {
                return;
            }
        }

        //
        // Step 3: Get the space large image if we can.
        //
        if (mAvatarId != null) {
            if ((mState & GET_SPACE_IMAGE) == 0) {
                mState |= GET_SPACE_IMAGE;

                Bitmap image = mTwinmeContext.getImageService().getImage(mAvatarId, ImageService.Kind.NORMAL);
                mState |= GET_SPACE_IMAGE_DONE;
                mAvatar = image;
                if (image != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onUpdateSpace(mSpace, image);
                        }
                    });
                }
                onOperation();
                return;
            }
            if ((mState & GET_SPACE_IMAGE_DONE) == 0) {
                return;
            }
        }

        hideProgressIndicator();
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

        if (operationId == GET_SPACE) {

            hideProgressIndicator();

            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnGetSpace(mObserver, null, null);
                return;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
