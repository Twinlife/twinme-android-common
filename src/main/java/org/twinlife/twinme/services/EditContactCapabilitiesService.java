/*
 *  Copyright (c) 2021-2024 twinlife SA.
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
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Capabilities;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class EditContactCapabilitiesService extends AbstractTwinmeService {
    private static final String LOG_TAG = "EditContactCapabili...";
    private static final boolean DEBUG = false;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;
    private static final int UPDATE_CONTACT = 1 << 2;
    private static final int UPDATE_CONTACT_DONE = 1 << 3;

    public interface Observer extends AbstractTwinmeService.Observer, AbstractTwinmeService.ContactObserver {
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            EditContactCapabilitiesService.this.onUpdateContact(contact);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            EditContactCapabilitiesService.this.onError(operationId, errorCode, errorParameter);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    private UUID mContactId;
    private Contact mContact;
    private Capabilities mCapabilities;
    private Capabilities mPrivateCapabilities;

    public EditContactCapabilitiesService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "EditIdentityService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " observer=" + observer);
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

    public void getContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContact: contactId=" + contactId);
        }

        mWork |= GET_CONTACT;
        mState &= ~(GET_CONTACT | GET_CONTACT_DONE);
        mContactId = contactId;
        showProgressIndicator();
        startOperation();
    }

    public void updateContact(@NonNull Contact contact, @NonNull Capabilities capabilities, @Nullable Capabilities privateCapabilities) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateContact: contact=" + contact + " capabilities=" + capabilities);
        }

        mWork |= UPDATE_CONTACT;
        mState &= ~(UPDATE_CONTACT | UPDATE_CONTACT_DONE);
        mContact = contact;
        mCapabilities = capabilities;
        mPrivateCapabilities = privateCapabilities;
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

        // We must get the contact.
        if ((mWork & GET_CONTACT) != 0) {
            if ((mState & GET_CONTACT) == 0) {
                mState |= GET_CONTACT;

                mTwinmeContext.getContact(mContactId, this::onGetContact);
                return;
            }
            if ((mState & GET_CONTACT_DONE) == 0) {
                return;
            }
        }

        // We must update identity for a contact
        if ((mWork & UPDATE_CONTACT) != 0 && mContact.getIdentityName() != null) {
            if ((mState & UPDATE_CONTACT) == 0) {
                mState |= UPDATE_CONTACT;

                long requestId = newOperation(UPDATE_CONTACT);

                mTwinmeContext.updateContactIdentity(requestId, mContact, mContact.getIdentityName(), mContact.getIdentityAvatarId(), mContact.getIdentityDescription(), mCapabilities, mPrivateCapabilities);
                return;
            }
            if ((mState & UPDATE_CONTACT_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        mState |= UPDATE_CONTACT_DONE;
        if ((mState & UPDATE_CONTACT) == 0 || (mState & UPDATE_CONTACT_DONE) != 0) {
            runOnUpdateContact(mObserver, contact, null);
        }
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

    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        if (operationId == GET_CONTACT) {

            hideProgressIndicator();

            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnGetContactNotFound(mObserver);
                return;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
