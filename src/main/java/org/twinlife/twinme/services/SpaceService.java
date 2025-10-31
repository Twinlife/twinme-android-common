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
import org.twinlife.twinlife.NotificationService.NotificationStat;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.TwinmeContext.Predicate;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.models.SpaceSettings;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SpaceService extends AbstractTwinmeService {
    private static final String LOG_TAG = "SpaceService";
    private static final boolean DEBUG = false;

    private static final int GET_SPACES = 1;
    private static final int GET_SPACES_DONE = 1 << 1;
    private static final int GET_SPACE = 1 << 4;
    private static final int GET_SPACE_DONE = 1 << 5;
    private static final int DELETE_SPACE = 1 << 6;
    private static final int DELETE_SPACE_DONE = 1 << 7;
    private static final int SET_CURRENT_SPACE = 1 << 8;
    private static final int SET_CURRENT_SPACE_DONE = 1 << 9;
    private static final int MOVE_CONTACT_SPACE = 1 << 10;
    private static final int MOVE_CONTACT_SPACE_DONE = 1 << 11;
    private static final int GET_CONTACT = 1 << 12;
    private static final int GET_CONTACT_DONE = 1 << 13;
    private static final int GET_SPACES_NOTIFICATIONS = 1 << 16;
    private static final int GET_SPACES_NOTIFICATIONS_DONE = 1 << 17;
    private static final int UPDATE_SPACE = 1 << 18;
    private static final int UPDATE_SPACE_DONE = 1 << 19;
    private static final int MOVE_GROUP_SPACE = 1 << 20;
    private static final int MOVE_GROUP_SPACE_DONE = 1 << 21;
    private static final int GET_GROUP = 1 << 22;
    private static final int GET_GROUP_DONE = 1 << 23;
    private static final int FIND_CONTACTS = 1 << 26;
    private static final int FIND_CONTACTS_DONE = 1 << 27;

    public interface Observer extends AbstractTwinmeService.Observer, SpaceObserver, CurrentSpaceObserver,
            ContactObserver, GroupObserver, ContactListObserver, GroupListObserver, SpaceListObserver {

        void onUpdateSpace(@NonNull Space space);

        void onCreateSpace(@NonNull Space space);

        void onDeleteSpace(@NonNull UUID spaceId);

        void onGetSpacesNotifications(@NonNull Map<Space, NotificationStat> spacesNotifications);

        void onUpdatePendingNotifications(boolean hasPendingNotification);

        void onUpdateProfile(@NonNull Profile profile);

        void onUpdateGroup(@NonNull Group group);

        void onCreateProfile(@NonNull Profile profile);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateSpace: requestId=" + requestId + " space=" + space);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                SpaceService.this.onCreateSpace(space);
            }
        }

        @Override
        public void onDeleteSpace(long requestId, @NonNull UUID spaceId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteSpace: requestId=" + requestId + " spaceId=" + spaceId);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                SpaceService.this.onDeleteSpace(spaceId);
            }
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            SpaceService.this.onSetCurrentSpace(space);
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Contact contact, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " contact=" + contact);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                SpaceService.this.onMoveToSpace(contact);
            }
        }

        @Override
        public void onUpdateSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateSpace: requestId=" + requestId + " space=" + space);
            }

            SpaceService.this.onUpdateSpace(space);
        }

        @Override
        public void onUpdateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateProfile: requestId=" + requestId + " profile=" + profile);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                SpaceService.this.onUpdateProfile(profile);
            }
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Group group, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " group=" + group);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                SpaceService.this.onMoveToSpace(group);
            }
        }

        @Override
        public void onCreateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateProfile: requestId=" + requestId + " profile=" + profile);
            }

            Integer operationId = getOperation(requestId);
            if (operationId != null) {
                SpaceService.this.onCreateProfile(profile);
            }
        }

        @Override
        public void onUpdatePendingNotifications(long requestId, boolean hasPendingNotifications) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdatePendingNotifications: requestId=" + requestId + " hasPendingNotifications=" + hasPendingNotifications);
            }

            SpaceService.this.onUpdatePendingNotifications(hasPendingNotifications);
        }
    }

    @Nullable
    private Observer mObserver;
    @Nullable
    private final UUID mSpaceId;
    private int mState = 0;
    private int mWork = 0;
    private Space mSpace;
    private List<Contact> mMoveContacts;
    private Contact mCurrentMoveContact;
    @Nullable
    private String mFindName;

    public SpaceService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                        @NonNull SpaceService.Observer observer, @Nullable UUID spaceId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "SpaceService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mSpaceId = spaceId;
        mObserver = observer;
        mTwinmeContextObserver = new SpaceService.TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public SpaceService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                        @NonNull SpaceService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "SpaceService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mSpaceId = null;
        mState = GET_SPACE | GET_SPACE_DONE;
        mObserver = observer;
        mTwinmeContextObserver = new SpaceService.TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void getSpaces() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpaces");
        }

        showProgressIndicator();

        mTwinmeContext.findSpaces((Space space) -> !space.isSecret(), (ErrorCode errorCode, List<Space> spaces) -> {
            mState |= GET_SPACES_DONE;
            if (spaces != null) {
                runOnGetSpaces(mObserver, spaces);
            }
            onOperation();
        });
    }

    public void findSpaceByName(String name) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findSpaceByName: " + name);
        }

        showProgressIndicator();

        String lowerCaseName = normalize(name);

        Predicate<Space> filter = (Space space) -> {
            String spaceName = normalize(space.getSpaceSettings().getName());

            return (name.equals(space.getName()) && space.isSecret()) || (spaceName.contains(lowerCaseName) && !space.isSecret());
        };

        mTwinmeContext.findSpaces(filter, (ErrorCode errorCode, List<Space> spaces) -> {
            if (spaces != null) {
                runOnGetSpaces(mObserver, spaces);
            }
            mState |= GET_SPACES_DONE;
            onOperation();
        });
    }

    public void findSpacesNotifications() {
        if (DEBUG) {
            Log.d(LOG_TAG, "findSpacesNotifications");
        }

        long requestId = newOperation(GET_SPACES_NOTIFICATIONS);
        if (DEBUG) {
            Log.d(LOG_TAG, "findSpacesNotifications: requestId=" + requestId);
        }
        showProgressIndicator();

        mTwinmeContext.getNotificationStats((ErrorCode errorCode, Map<Space, NotificationStat> stats) -> {
            runOnUiThread(() -> {
                if (mObserver != null && stats != null) {
                    mObserver.onGetSpacesNotifications(stats);
                }
            });
            mState |= GET_SPACES_NOTIFICATIONS_DONE;
            onOperation();
        });
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

    public void moveContactsInSpace(List<Contact> contacts, Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "moveContactsInSpace: contacts=" + contacts + " space=" + space);
        }

        mMoveContacts = contacts;
        mSpace = space;
        mWork |= MOVE_CONTACT_SPACE;
        mState &= ~(MOVE_CONTACT_SPACE | MOVE_CONTACT_SPACE_DONE);
        showProgressIndicator();

        startOperation();
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

    public void moveContactToSpace(@NonNull Space space, @NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "moveContact: space= " + space + " contact= " + contact);
        }

        long requestId = newOperation(MOVE_CONTACT_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "moveContact: requestId=" + requestId + " space= " + space);
        }
        showProgressIndicator();

        mTwinmeContext.moveToSpace(requestId, contact, space);
    }

    public void moveGroupToSpace(@NonNull Space space, @NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "moveGroupToSpace: space= " + space + " group= " + group);
        }

        long requestId = newOperation(MOVE_GROUP_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "moveToSpace: requestId=" + requestId + " space= " + space);
        }
        showProgressIndicator();

        mTwinmeContext.moveToSpace(requestId, group, space);
    }

    private void nextMoveContact() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextMoveContact");
        }

        if (mMoveContacts == null || mMoveContacts.isEmpty()) {
            mCurrentMoveContact = null;
            mState |= MOVE_CONTACT_SPACE | MOVE_CONTACT_SPACE_DONE;

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onUpdateSpace(mSpace);
                }
            });
        } else {
            mCurrentMoveContact = mMoveContacts.remove(0);
            mState &= ~(MOVE_CONTACT_SPACE | MOVE_CONTACT_SPACE_DONE);
        }
    }

    public void getAllContacts() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAllContacts");
        }

        showProgressIndicator();

        Filter<RepositoryObject> filter = new Filter<RepositoryObject>(null) {
            @Override
            public boolean accept(@NonNull RepositoryObject object) {

                if (!(object instanceof Contact)) {
                    return false;
                }
                Contact contact = (Contact) object;
                return contact.getSpace() != null && !contact.getSpace().isSecret();
            }
        };

        mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
            runOnGetContacts(mObserver, contacts);
            onOperation();
        });
    }

    public void findContactsBySpace(Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactsBySpace: " + space);
        }

        showProgressIndicator();

        Filter<RepositoryObject> filter = new Filter<>(space);

        mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
            runOnGetContacts(mObserver, contacts);
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
            onOperation();
        });
    }

    public void getContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContact: contactId= " + contactId);
        }

        showProgressIndicator();
        mTwinmeContext.getContact(contactId, this::onGetContact);
    }

    public void getGroup(@NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroup: groupId= " + groupId);
        }

        long requestId = newOperation(GET_GROUP);
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroup: requestId=" + requestId + " groupId= " + groupId);
        }
        showProgressIndicator();

        mTwinmeContext.getGroup(groupId, this::onGetGroup);
    }

    public void findContactsByName(@NonNull String name) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactsByName: name=" + name);
        }

        showProgressIndicator();

        mWork |= FIND_CONTACTS;
        mState &= ~(FIND_CONTACTS | FIND_CONTACTS_DONE);
        mFindName = normalize(name);

        startOperation();
    }

    private void onCreateSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateSpace: space=" + space);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateSpace(space);
            }
        });
        onOperation();
    }

    private void onCreateProfile(@NonNull Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateProfile");
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateProfile(profile);
            }
        });
        onOperation();
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact: contact=" + contact);
        }

        mState |= GET_CONTACT_DONE;

        if (contact != null) {
            runOnGetContact(mObserver, contact, null);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_CONTACT, errorCode, null);
        }
        onOperation();
    }

    private void onMoveToSpace(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveToSpace: contact=" + contact);
        }

        mState |= MOVE_CONTACT_SPACE_DONE;

        runOnUpdateContact(mObserver, contact, null);
        nextMoveContact();
        onOperation();
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

        if (space.getId().equals(mSpaceId)) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onUpdateSpace(space);
                }
            });
        }

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
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }

    private void onGetGroup(@NonNull ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroup: group=" + group);
        }

        mState |= GET_GROUP_DONE;
        if (group != null) {
            runOnGetGroup(mObserver, group, null);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetGroupNotFound(mObserver);
        } else {
            onError(GET_GROUP, errorCode, null);
        }
        onOperation();
    }

    private void onMoveToSpace(@NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveToSpace: group=" + group);
        }

        mState |= MOVE_GROUP_SPACE_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateGroup(group);
            }
        });

        onOperation();
    }

    private void onUpdatePendingNotifications(boolean hasPendingNotifications) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdatePendingNotifications: hasPendingNotifications=" + hasPendingNotifications);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdatePendingNotifications(hasPendingNotifications);
            }
        });
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        //
        // Step 1: get the current space or the space represented by mSpaceId.
        //

        if ((mState & GET_SPACE) == 0) {
            mState |= GET_SPACE;

            if (mSpaceId == null) {

                mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                    runOnGetSpace(mObserver, space, null);
                    mState |= GET_SPACE_DONE;
                    onOperation();
                });
                return;
            } else {

                mTwinmeContext.getSpace(mSpaceId, (ErrorCode errorCode, Space space) -> {
                    runOnGetSpace(mObserver, space, null);
                    mState |= GET_SPACE_DONE;
                    onOperation();
                });
                return;
            }
        }
        if ((mState & GET_SPACE_DONE) == 0) {
            return;
        }

        if (((mWork & MOVE_CONTACT_SPACE) != 0) && (mSpace != null)) {
            if ((mState & MOVE_CONTACT_SPACE) == 0) {

                if (mCurrentMoveContact == null) {
                    nextMoveContact();
                }

                mState |= MOVE_CONTACT_SPACE;
                if (mCurrentMoveContact != null) {
                    long requestId = newOperation(MOVE_CONTACT_SPACE);
                    if (DEBUG) {
                        Log.d(LOG_TAG, "moveContact: requestId=" + requestId + " space= " + mSpace);
                    }

                    mTwinmeContext.moveToSpace(requestId, mCurrentMoveContact, mSpace);
                    return;
                }
            }
            if ((mState & MOVE_CONTACT_SPACE_DONE) == 0) {
                return;
            }
        }

        //
        // We must get the list of contacts by name.
        //
        if ((mWork & FIND_CONTACTS) != 0 && mFindName != null) {
            if ((mState & FIND_CONTACTS) == 0) {
                mState |= FIND_CONTACTS;

                Filter<RepositoryObject> filter = new Filter<RepositoryObject>(null) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {

                        if (!(object instanceof Contact)) {
                            return false;
                        }
                        final Contact contact = (Contact) object;
                        final String contactName = normalize(contact.getName());
                        final Space space = contact.getSpace();
                        return contactName.contains(mFindName) && space != null && !space.isSecret();
                    }
                };

                mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
                    runOnGetContacts(mObserver, contacts);
                    mState |= FIND_CONTACTS_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & FIND_CONTACTS_DONE) == 0) {
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

        if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            if (operationId == DELETE_SPACE) {
                runOnGetSpace(mObserver, null, null);
                return;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
