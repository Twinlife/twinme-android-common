/*
 *  Copyright (c) 2019-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.List;
import java.util.UUID;

public class ContactsService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ContactsService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_CONTACTS = 1 << 2;
    private static final int GET_CONTACTS_DONE = 1 << 3;
    private static final int FIND_CONTACTS = 1 << 4;
    private static final int FIND_CONTACTS_DONE = 1 << 5;

    public interface Observer extends AbstractTwinmeService.Observer, AbstractTwinmeService.ContactListObserver,
            AbstractTwinmeService.CurrentSpaceObserver, ContactObserver {

        void onCreateContact(@NonNull Contact contact, @Nullable Bitmap avatar);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            ContactsService.this.onCreateContact(contact);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            ContactsService.this.onUpdateContact(contact);
        }

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            ContactsService.this.onDeleteContact(contactId);
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Contact contact, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " contact=" + contact + " oldSpace=" + oldSpace);
            }

            ContactsService.this.onMoveContact(contact);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            ContactsService.this.onSetCurrentSpace(space);
        }
    }

    @Nullable
    private ContactsService.Observer mObserver;
    private Space mSpace;
    private int mState = 0;
    private int mWork = 0;
    @Nullable
    private String mFindName;

    public ContactsService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull ContactsService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ContactsService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new ContactsService.TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
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

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        if (mSpace != space) {
            mSpace = space;
            mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE);
        }
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }

    private void onCreateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateContact: contact=" + contact);
        }

        if (contact.getSpace() == mSpace) {
            Bitmap avatar = getImage(contact);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onCreateContact(contact, avatar);
                }
            });
            if (avatar == null && contact.getAvatarId() != null) {
                getImageFromServer(contact);
            }
        }
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        if (contact.getSpace() == mSpace) {
            Bitmap avatar = getImage(contact);
            runOnUpdateContact(mObserver, contact, avatar);
            if (avatar == null && contact.getAvatarId() != null) {
                getImageFromServer(contact);
            }
        }
    }

    private void onMoveContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveContact: contact=" + contact);
        }

        if (contact.getSpace() != mSpace) {
            runOnDeleteContact(mObserver, contact.getId());
        }
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        runOnDeleteContact(mObserver, contactId);
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: Get the current space.
        //
        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                mState |= GET_CURRENT_SPACE_DONE;
                mSpace = space;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2: We must get the list of contacts for the space.
        //
        if ((mState & GET_CONTACTS) == 0) {
            mState |= GET_CONTACTS;

            final Filter<RepositoryObject> filter = mTwinmeContext.createSpaceFilter();
            mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
                runOnGetContacts(mObserver, contacts);
                mState |= GET_CONTACTS_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CONTACTS_DONE) == 0) {
            return;
        }

        //
        // Step 3: We must get the list of contacts for the space.
        //
        if ((mWork & FIND_CONTACTS) != 0 && mFindName != null) {
            if ((mState & FIND_CONTACTS) == 0) {
                mState |= FIND_CONTACTS;

                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Contact)) {
                            return false;
                        }

                        String contactName = normalize(object.getName());
                        return contactName.contains(mFindName);
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

        hideProgressIndicator();
    }
}
