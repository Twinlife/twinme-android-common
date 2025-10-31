/*
 *  Copyright (c) 2019-2024 twinlife SA.
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
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.models.SpaceSettings;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EditSpaceService extends AbstractTwinmeService {
    private static final String LOG_TAG = "EditSpaceService";
    private static final boolean DEBUG = false;

    private static final int GET_SPACES = 1;
    private static final int GET_SPACES_DONE = 1 << 1;
    private static final int GET_SPACE = 1 << 4;
    private static final int GET_SPACE_DONE = 1 << 5;
    private static final int DELETE_SPACE = 1 << 6;
    private static final int DELETE_SPACE_DONE = 1 << 7;
    private static final int SET_CURRENT_SPACE = 1 << 8;
    private static final int SET_CURRENT_SPACE_DONE = 1 << 9;
    private static final int GET_CONTACTS_DONE = 1 << 11;
    private static final int UPDATE_SPACE = 1 << 12;
    private static final int UPDATE_SPACE_DONE = 1 << 13;
    private static final int GET_GROUPS_DONE = 1 << 15;
    private static final int GET_SPACE_IMAGE = 1 << 16;
    private static final int GET_SPACE_IMAGE_DONE = 1 << 17;
    private static final int CREATE_SPACE = 1 << 18;
    private static final int CREATE_SPACE_DONE = 1 << 19;

    public interface Observer extends AbstractTwinmeService.Observer, SpaceObserver, CurrentSpaceObserver,
            AbstractTwinmeService.ContactListObserver, AbstractTwinmeService.GroupListObserver {

        void onCreateSpace(@NonNull Space space);

        void onUpdateSpace(Space space);

        void onDeleteSpace(@NonNull UUID spaceId);

        void onCreateProfile(@NonNull Profile profile);

        void onUpdateProfile(@NonNull Profile profile);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateSpace: requestId=" + requestId + " space=" + space);
            }
            
            EditSpaceService.this.onCreateSpace(space);
        }

        @Override
        public void onDeleteSpace(long requestId, @NonNull UUID spaceId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteSpace: requestId=" + requestId + " spaceId=" + spaceId);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            EditSpaceService.this.onDeleteSpace(spaceId);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            EditSpaceService.this.onSetCurrentSpace(space);
        }

        @Override
        public void onUpdateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateSpace: requestId=" + requestId + " space=" + space);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            EditSpaceService.this.onUpdateSpace(space);
        }

        @Override
        public void onCreateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateProfile: requestId=" + requestId + " profile=" + profile);
            }

            EditSpaceService.this.onCreateProfile(profile);
        }

        @Override
        public void onUpdateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateProfile: requestId=" + requestId + " profile=" + profile);
            }

            EditSpaceService.this.onUpdateProfile(profile);
        }
    }

    @Nullable
    private Observer mObserver;
    @Nullable
    private UUID mSpaceId;
    @Nullable
    private ImageId mAvatarId;

    private Space mSpace;
    private Bitmap mAvatar;
    private File mAvatarFile;
    private SpaceSettings mSpaceSettings;
    private final List<Space> mSpaces = new ArrayList<>();
    private int mState = 0;
    private int mWork = 0;

    public EditSpaceService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                            @NonNull Observer observer, @Nullable UUID spaceId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "EditSpaceService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mSpaceId = spaceId;
        mTwinmeContextObserver = new EditSpaceService.TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void createSpace(SpaceSettings spaceSettings, @Nullable Bitmap spaceAvatar, @Nullable File spaceAvatarFile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createSpace: spaceSettings= " + spaceSettings);
        }

        showProgressIndicator();

        mSpaceSettings = spaceSettings;
        mAvatar = spaceAvatar;
        mAvatarFile = spaceAvatarFile;

        mWork |= CREATE_SPACE;
        mState &= ~(CREATE_SPACE | CREATE_SPACE_DONE);

        startOperation();
    }

    public void updateSpace(Space space, SpaceSettings spaceSettings, @Nullable Bitmap spaceAvatar, @Nullable File spaceAvatarFile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateSpace: space= " + space + " spaceSettings= " + spaceSettings);
        }

        long requestId = newOperation(UPDATE_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "updateSpace: requestId=" + requestId + " spaceSettings= " + spaceSettings + "space= " + space + " spaceSettings= " + spaceSettings);
        }
        showProgressIndicator();

        mTwinmeContext.updateSpace(requestId, space, spaceSettings, spaceAvatar, spaceAvatarFile);
    }

    public void setSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: space= " + space);
        }

        long requestId = newOperation(SET_CURRENT_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: requestId=" + requestId + " space= " + space);
        }
        showProgressIndicator();

        mTwinmeContext.setCurrentSpace(requestId, space);
        if (!space.isSecret()) {
            mTwinmeContext.setDefaultSpace(space);
        }
    }

    public void deleteSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteSpace: space= " + space);
        }

        long requestId = newOperation(DELETE_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteSpace: requestId=" + requestId + " space= " + space);
        }
        showProgressIndicator();

        mTwinmeContext.deleteSpace(requestId, space);
    }

    public void findContactsBySpace(Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactsBySpace: " + space);
        }

        showProgressIndicator();

        Filter<RepositoryObject> filter = new Filter<>(space);

        mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
            runOnGetContacts(mObserver, contacts);
            mState |= GET_CONTACTS_DONE;
            onOperation();
        });
    }

    public void findGroupsBySpace(Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findGroupsBySpace: " + space);
        }

        showProgressIndicator();

        Filter<RepositoryObject> filter = new Filter<>(space);

        mTwinmeContext.findGroups(filter, (List<Group> groups) -> {
            runOnGetGroups(mObserver, groups);
            mState |= GET_GROUPS_DONE;
            onOperation();
        });
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

    public void updateDefaultSpace(Space oldDefaultSpace) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDefaultSpace : " + oldDefaultSpace);
        }

        for (Space space : mSpaces) {
            if (!space.getSpaceSettings().isSecret() && !space.getId().equals(oldDefaultSpace.getId())) {
                setSpace(space);
            }
        }
    }

    private void onDeleteSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteSpace: spaceId=" + spaceId);
        }

        mState |= DELETE_SPACE_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteSpace(spaceId);
            }
        });

        onOperation();
    }

    private void onUpdateSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateSpace: space=" + space);
        }

        mState |= UPDATE_SPACE_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateSpace(space);
            }
        });

        onOperation();
    }

    private void onCreateProfile(@NonNull Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateProfile: profile=" + profile);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateProfile(profile);
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
        onOperation();
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        mState |= SET_CURRENT_SPACE_DONE;
        mSpaceId = space.getId();
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }

    private void onGetSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetSpace: space=" + space);
        }

        mState |= GET_SPACE_DONE;

        mSpace = space;
        if (space != null) {
            mAvatarId = space.getAvatarId();
            mAvatar = getSpaceImage(space, ImageService.Kind.NORMAL);
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

    private void onCreateSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateSpace: space=" + space);
        }

        mState |= CREATE_SPACE_DONE;

        mSpace = space;
        mSpaceId = space.getId();

        long requestId = newOperation(SET_CURRENT_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: requestId=" + requestId + " space= " + space);
        }
        mTwinmeContext.setCurrentSpace(requestId, space);
        if (!space.isSecret()) {
            mTwinmeContext.setDefaultSpace(space);
        }
        if (mObserver != null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onCreateSpace(mSpace);
                }
            });
        }

        onOperation();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        //
        // Step 1: get spaces.
        //

        if ((mState & GET_SPACES) == 0) {
            mState |= GET_SPACES;

            mTwinmeContext.findSpaces((Space space) -> true, (ErrorCode errorCode, List<Space> spaces)-> {
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
        // Step 2: get the current space or the space represented by mSpaceId.
        //

        if ((mState & GET_SPACE) == 0) {
            mState |= GET_SPACE;

            if (mSpaceId == null) {
                mTwinmeContext.getCurrentSpace(this::onGetSpace);
            } else {
                mTwinmeContext.getSpace(mSpaceId, this::onGetSpace);
            }
            return;
        }
        if ((mState & GET_SPACE_DONE) == 0) {
            return;
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
                    runOnGetSpace(mObserver, mSpace, image);
                }
                onOperation();
                return;
            }
            if ((mState & GET_SPACE_IMAGE_DONE) == 0) {
                return;
            }
        }

        if ((mWork & CREATE_SPACE) != 0) {
            if ((mState & CREATE_SPACE) == 0) {
                mState |= CREATE_SPACE;
                long requestId = newOperation(CREATE_SPACE);
                mTwinmeContext.createSpace(requestId, mSpaceSettings, mAvatar, mAvatarFile);
                return;
            }
            if ((mState & CREATE_SPACE_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }


    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        if (errorCode == ErrorCode.ITEM_NOT_FOUND && operationId == DELETE_SPACE) {
            runOnGetSpace(mObserver, null, null);
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
