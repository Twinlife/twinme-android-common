/*
 *  Copyright (c) 2022-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.configuration.AppFlavor;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CallParticipantService extends AbstractTwinmeService {
    private static final String LOG_TAG = "CallParticipantService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_CONTACTS = 1 << 2;
    private static final int GET_CONTACTS_DONE = 1 << 3;
    private static final int FIND_CONTACTS = 1 << 4;
    private static final int FIND_CONTACTS_DONE = 1 << 5;
    private static final int GET_SPACE = 1 << 6;
    private static final int GET_SPACE_DONE = 1 << 7;
    private static final int GET_SPACES = 1 << 8;
    private static final int GET_SPACES_DONE = 1 << 9;

    public interface Observer extends AbstractTwinmeService.Observer, ContactListObserver, SpaceObserver {
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {
        @Override
        public void onTwinlifeReady() {
            if(AppFlavor.TWINME){
                //Twinme doesn't support spaces
                CallParticipantService.this.mSpace = null;
                CallParticipantService.this.mState |= GET_SPACES;
                CallParticipantService.this.mState |= GET_SPACES_DONE;
                CallParticipantService.this.mState |= GET_CURRENT_SPACE;
                CallParticipantService.this.mState |= GET_CURRENT_SPACE_DONE;
            }

            super.onTwinlifeReady();
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            CallParticipantService.this.onSetCurrentSpace(space);
        }
    }

    @Nullable
    private Observer mObserver;
    private Space mSpace;

    private int mState = 0;
    private int mWork = 0;
    private final List<Space> mSpaces = new ArrayList<>();

    @Nullable
    private String mFindName;

    public CallParticipantService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
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

    public void getSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: spaceId=" + spaceId);
        }

        long requestId = newOperation(GET_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: requestId=" + requestId + " spaceId=" + spaceId);
        }
        showProgressIndicator();

        mTwinmeContext.getSpace(spaceId, (ErrorCode errorCode, Space space) -> {
            mState |= GET_SPACE_DONE;
            mSpace = space;
            mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE);
            if (space != null) {
                runOnGetSpace(mObserver, space, null);
            }
            onOperation();
        });
    }

    public void getContacts() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContacts");
        }

        mWork |= GET_CONTACTS;
        mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE);
        showProgressIndicator();
        startOperation();
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

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        if (mSpace != space) {
            mSpace = space;
            mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE);
        }
        onOperation();
    }

    private void onGetSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;
        mSpace = space;
        runOnGetSpace(mObserver, space, null);
        onOperation();
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
        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            mTwinmeContext.getCurrentSpace(this::onGetSpace);
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        // We must get the list of contacts.
        if ((mWork & GET_CONTACTS) != 0) {
            if ((mState & GET_CONTACTS) == 0) {
                mState |= GET_CONTACTS;
                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        final Contact contact = (Contact) object;

                        return !contact.isTwinroom() && contact.hasPeer();
                    }
                };
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
        }

        //
        // We must get the list of contacts by name.
        //
        if ((mWork & FIND_CONTACTS) != 0 && mFindName != null) {
            if ((mState & FIND_CONTACTS) == 0) {
                mState |= FIND_CONTACTS;

                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        final Contact contact = (Contact) object;

                        return !contact.isTwinroom() && contact.hasPeer();
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
}