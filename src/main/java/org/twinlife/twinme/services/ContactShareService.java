/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AndroidImageTools;
import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.InvitationDescriptor.Status;
import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class ContactShareService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ContactShareService";
    private static final boolean DEBUG = false;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;
    private static final int GET_OR_CREATE_CONVERSATION = 1 << 2;
    private static final int GET_SHARE_CONTACT = 1 << 3;
    private static final int GET_SHARE_CONTACT_DONE = 1 << 4;
    private static final int PUSH_SHARE_CONTACT = 1 << 5;
    private static final int ANSWER_SHARE_CONTACT = 1 << 6;
    private static final int GET_DESCRIPTOR = 1 << 7;
    private static final int GET_DESCRIPTOR_DONE = 1 << 8;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onErrorNoPermission();

        void onErrorFeatureNotSupportedByPeer();
    }


    private class ContactShareServiceObserver extends org.twinlife.twinlife.ConversationService.DefaultServiceObserver {

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ContactShareServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            if (requestId == BaseService.DEFAULT_REQUEST_ID) {
                if (errorCode == ErrorCode.FEATURE_NOT_SUPPORTED_BY_PEER) {
                    UUID conversationId;
                    try {
                        conversationId = UUID.fromString(errorParameter);
                    } catch (Exception exception) {
                        conversationId = null;
                    }
                    if (conversationId != null && conversationId.equals(ContactShareService.this.mConversationId)) {

                        ContactShareService.this.onConversationError(errorCode, errorParameter);
                        onOperation();
                    }
                } else {
                    mTwinmeContext.assertion(ServiceAssertPoint.ON_ERROR, AssertPoint.create(this.getClass()).put(errorCode));
                }
            } else {
                Integer operationId = getOperation(requestId);
                if (operationId == null) {

                    return;
                }

                ContactShareService.this.onError(operationId, errorCode, errorParameter);
                onOperation();
            }
        }
    }

    @Nullable
    private Observer mObserver;
    @Nullable
    private UUID mContactId;
    @Nullable
    private UUID mShareContactId;
    @Nullable
    private Originator mContact;
    @Nullable
    private Originator mShareContact;
    @Nullable
    private byte[] mShareContactAvatar;
    @Nullable
    private UUID mConversationId;
    @Nullable
    private Conversation mConversation;
    @Nullable
    private DescriptorId mContactShareDescriptorId;
    @Nullable
    private ConversationService.ContactShareDescriptor mContactShareDescriptor;
    @Nullable
    private Status mAnswer;

    private boolean mFeatureNotSupportedByPeerMessage = true;
    private int mState = 0;
    @NonNull
    private final ContactShareServiceObserver mContactShareServiceObserver;

    @NonNull
    private final AndroidImageTools mAndroidImageTools = new AndroidImageTools();

    public ContactShareService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ContactShareService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mContactShareServiceObserver = new ContactShareServiceObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    /**
     * Create and push a {@link ConversationService.ContactShareDescriptor}, to ask the current conversation's contact whether we can share their invitation.
     *
     * @param contactId      The current conversation's contact ID.
     * @param shareContactId ID of the contact with whom we want to share the current conversation's contact.
     */
    public void pushContactShare(@NonNull UUID contactId, @NonNull UUID shareContactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushContactShare: contactId=" + contactId + " shareContactId=" + shareContactId);
        }

        synchronized (this) {
            mContactId = contactId;
            mShareContactId = shareContactId;

            mState &= ~(GET_CONTACT | GET_CONTACT_DONE | GET_OR_CREATE_CONVERSATION | GET_SHARE_CONTACT | GET_SHARE_CONTACT_DONE | PUSH_SHARE_CONTACT);
        }

        startOperation();
    }

    public void answerContactShare(@NonNull UUID contactId, @NonNull DescriptorId descriptorId, @NonNull Status answer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "answerContactShare: contactId=" + contactId + " descriptorId=" + descriptorId + " answer=" + answer);
        }

        synchronized (this) {
            mContactId = contactId;
            mContactShareDescriptorId = descriptorId;
            mAnswer = answer;

            mState &= ~(GET_CONTACT | GET_CONTACT_DONE | GET_DESCRIPTOR | GET_DESCRIPTOR_DONE | GET_OR_CREATE_CONVERSATION | ANSWER_SHARE_CONTACT);
        }
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getConversationService().removeServiceObserver(mContactShareServiceObserver);
        }

        mObserver = null;
        super.dispose();
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
        // Step 1
        //
        if (mContactId != null) {
            if ((mState & GET_CONTACT) == 0) {
                mState |= GET_CONTACT;

                mTwinmeContext.getContact(mContactId, this::onGetContact);
                return;
            }
            if ((mState & GET_CONTACT_DONE) == 0) {
                return;
            }
        }

        //
        // Step 2
        //
        if ((mState & GET_OR_CREATE_CONVERSATION) == 0) {
            mState |= GET_OR_CREATE_CONVERSATION;

            if (mContact != null) {

                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationService.getOrCreateConversation: contact=" + mContact);
                }
                mConversation = mTwinmeContext.getConversationService().getOrCreateConversation(mContact);
                if (mConversation == null) {
                    onError(GET_OR_CREATE_CONVERSATION, ErrorCode.ITEM_NOT_FOUND, null);
                    return;
                }

                mConversationId = mConversation.getId();
            }
        }

        //
        // Step 3
        //

        if (mShareContactId != null) {
            if ((mState & GET_SHARE_CONTACT) == 0) {
                mState |= GET_SHARE_CONTACT;

                mTwinmeContext.getContact(mShareContactId, this::onGetShareContact);
                return;
            }
            if ((mState & GET_SHARE_CONTACT_DONE) == 0) {
                return;
            }
        }

        //
        // Step 4
        //
        if (mShareContact != null) {
            if ((mState & PUSH_SHARE_CONTACT) == 0) {
                mState |= PUSH_SHARE_CONTACT;

                long requestId = newOperation(PUSH_SHARE_CONTACT);

                if (mConversation == null || mShareContactAvatar == null || mShareContactId == null) {
                    onError(PUSH_SHARE_CONTACT, ErrorCode.ITEM_NOT_FOUND, null);
                } else {
                    mTwinmeContext.pushContactShare(requestId, mConversation, null, mShareContact.getName(), mShareContactAvatar, mShareContactId,0);
                }
            }
        }

        //
        // Answer contact share (accept / deny)
        //
        if (mContactShareDescriptorId != null) {

            if ((mState & GET_DESCRIPTOR) == 0) {
                mState |= GET_DESCRIPTOR;

                mTwinmeContext.getDescriptor(mContactShareDescriptorId, this::onGetDescriptor);
                return;
            }

            if ((mState & GET_DESCRIPTOR_DONE) == 0) {
                return;
            }

            if ((mState & ANSWER_SHARE_CONTACT) == 0) {
                mState |= ANSWER_SHARE_CONTACT;
                if (mConversation != null && mContactShareDescriptor != null && mAnswer != null) {
                    Space space = null;
                    if (mContact != null) {
                        space = mContact.getSpace();
                    }

                    mTwinmeContext.answerContactShare(mConversation, mContactShareDescriptor, space, mAnswer, false);
                }
            }
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    private void onGetDescriptor(@NonNull ErrorCode errorCode, @Nullable Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetDescriptor: errorCode=" + errorCode + " descriptor=" + descriptor);
        }

        mState |= GET_DESCRIPTOR_DONE;

        if (errorCode != ErrorCode.SUCCESS || !(descriptor instanceof ConversationService.ContactShareDescriptor)) {
            onError(GET_DESCRIPTOR, errorCode, null);
        } else {
            mContactShareDescriptor = (ConversationService.ContactShareDescriptor) descriptor;
        }

        onOperation();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getConversationService().addServiceObserver(mContactShareServiceObserver);
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact contact=" + contact);
        }

        mState |= GET_CONTACT_DONE;

        mContact = contact;
        if (contact == null) {
            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnGetContactNotFound(mObserver);
            } else {
                onError(GET_CONTACT, errorCode, null);
            }
        }
        onOperation();
    }

    private void onGetShareContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetShareContact: errorCode=" + errorCode + " contact=" + contact);
        }

        mState |= GET_SHARE_CONTACT_DONE;

        if (mConversation == null) {
            Log.e(LOG_TAG, "mConversation is null, cannot send contact share for contact: " + contact);
            onOperation();
            return;
        }

        if (contact != null) {
            mShareContact = contact;

            Bitmap avatar = getImage(contact);
            if (avatar == null) {
                avatar = mTwinmeApplication.getDefaultAvatar();
            }

            mShareContactAvatar = mAndroidImageTools.getImageData(avatar);

        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND && mShareContactId != null) {
            runOnGetShareContactNotFound(mShareContactId, mObserver);
        } else {
            onError(GET_SHARE_CONTACT, errorCode, null);
        }
        onOperation();
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

        if ((errorCode == ErrorCode.ITEM_NOT_FOUND || errorCode == ErrorCode.EXPIRED) && operationId == GET_CONTACT) {
            if (errorParameter != null) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(errorParameter);
                } catch (Exception exception) {
                    uuid = null;
                }

                mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, mContactId, uuid);
            }
            runOnGetContactNotFound(mObserver);
            return;
        }

        if (errorCode == ErrorCode.NO_PERMISSION) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onErrorNoPermission();
                }
            });
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }

    private void onConversationError(ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConversationError: errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        if (mFeatureNotSupportedByPeerMessage) {
            mFeatureNotSupportedByPeerMessage = false;
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onErrorFeatureNotSupportedByPeer();
                }
            });
        }
    }
}
