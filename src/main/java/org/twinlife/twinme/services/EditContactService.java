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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class EditContactService extends ShowContactService {
    private static final String LOG_TAG = "EditContactService";
    private static final boolean DEBUG = false;

    private static final int UPDATE_CONTACT = 1 << 8;
    private static final int UPDATE_CONTACT_DONE = 1 << 9;

    public interface Observer extends ShowContactService.Observer {
    }

    @Nullable
    private String mContactName;
    @Nullable
    private String mContactDescription;

    public EditContactService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
                              @NonNull UUID contactId) {
        super(activity, twinmeContext, observer, contactId);
        if (DEBUG) {
            Log.d(LOG_TAG, "EditContactService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }
    }

    public void updateContact(@NonNull Contact contact, @NonNull String contactName, @Nullable String contactDescription) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContact: contact=" + contact + " contactName=" + contactName + " contactDescription=" + contactDescription);
        }

        mWork |= UPDATE_CONTACT;
        mState &= ~(UPDATE_CONTACT | UPDATE_CONTACT_DONE);
        mContact = contact;
        mContactName = contactName;
        mContactDescription = contactDescription;
        showProgressIndicator();
        startOperation();
    }

    //
    // Private methods
    //
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        //
        // Work action: we must update the contact name.
        //
        if (mContact != null && mContactName != null && (mWork & UPDATE_CONTACT) != 0) {
            if ((mState & UPDATE_CONTACT) == 0) {
                mState |= UPDATE_CONTACT;
                long requestId = newOperation(UPDATE_CONTACT);
                mTwinmeContext.updateContact(requestId, mContact, mContactName, mContactDescription);
                return;
            }
            if ((mState & UPDATE_CONTACT_DONE) == 0) {
                return;
            }
            mWork &= ~UPDATE_CONTACT;
        }

        super.onOperation();
    }

    protected void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        mState |= UPDATE_CONTACT_DONE;
        super.onUpdateContact(contact);
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

        if (errorCode == ErrorCode.ITEM_NOT_FOUND && operationId == UPDATE_CONTACT) {
            mState |= UPDATE_CONTACT_DONE;

            runOnGetContactNotFound(mObserver);
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
