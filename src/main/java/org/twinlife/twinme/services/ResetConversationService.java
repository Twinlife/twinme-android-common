/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class ResetConversationService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ResetConversation...";
    private static final boolean DEBUG = false;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;
    private static final int GET_GROUP = 1 << 2;
    private static final int GET_GROUP_DONE = 1 << 3;
    private static final int GET_OR_CREATE_CONVERSATION = 1 << 4;
    private static final int GET_OR_CREATE_CONVERSATION_DONE = 1 << 5;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onResetConversation(@NonNull org.twinlife.twinlife.ConversationService.Conversation conversation, @NonNull org.twinlife.twinlife.ConversationService.ClearMode clearMode);

        void onGetGroup(@NonNull Group group);

        void onGetGroupNotFound();
    }

    private class ResetConversationServiceObserver extends org.twinlife.twinlife.ConversationService.DefaultServiceObserver {

        @Override
        public void onResetConversation(@NonNull org.twinlife.twinlife.ConversationService.Conversation conversation, @NonNull org.twinlife.twinlife.ConversationService.ClearMode clearMode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ResetConversationServiceObserver.onResetConversation: conversation=" + conversation);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ResetConversationService.this.onResetConversation(conversation, clearMode);
        }

        @Override
        public void onError(long requestId, BaseService.ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ResetConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            ResetConversationService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @Nullable
    private Observer mObserver;
    private UUID mContactId;
    @Nullable
    private Originator mContact;
    @Nullable
    private Group mGroup;
    @Nullable
    private UUID mTwincodeOutboundId;
    @Nullable
    private UUID mConversationId;
    @Nullable
    private org.twinlife.twinlife.ConversationService.Conversation mConversation;
    private int mState = 0;
    private final ResetConversationServiceObserver mResetConversationServiceObserver;

    public ResetConversationService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull ResetConversationService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ResetConversationService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mResetConversationServiceObserver = new ResetConversationServiceObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }


    public void getContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContact: contactId=" + contactId);
        }

        mContactId = contactId;
        mState &= ~(GET_CONTACT | GET_CONTACT_DONE | GET_OR_CREATE_CONVERSATION | GET_OR_CREATE_CONVERSATION_DONE);
        mState |= GET_GROUP_DONE | GET_GROUP;

        startOperation();
    }

    public void getGroup(@NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroup: groupId=" + groupId);
        }

        mContactId = groupId;
        mState &= ~(GET_GROUP | GET_GROUP_DONE | GET_OR_CREATE_CONVERSATION | GET_OR_CREATE_CONVERSATION_DONE);
        mState |= GET_CONTACT_DONE | GET_CONTACT;

        startOperation();
    }

    public void resetConversation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "resetConversation");
        }

        if (mConversation != null) {
            mTwinmeContext.execute(() -> {
                // Clear both sides of the conversation (ignore errors).
                mTwinmeContext.getConversationService().clearConversation(mConversation, System.currentTimeMillis(), org.twinlife.twinlife.ConversationService.ClearMode.CLEAR_BOTH);
            });
        }
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getConversationService().removeServiceObserver(mResetConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    //
    // Private methods
    //

    private boolean isConversation(@Nullable org.twinlife.twinlife.ConversationService.Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isConversation conversation=" + conversation);
        }

        // For some reason, we can have a null conversation object.
        return conversation != null && conversation.isConversation(mConversationId);
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
        // Step 2:
        //

        if (mContactId != null) {
            if ((mState & GET_GROUP) == 0) {
                mState |= GET_GROUP;

                mTwinmeContext.getGroup(mContactId, this::onGetGroup);
                return;
            }
            if ((mState & GET_GROUP_DONE) == 0) {
                return;
            }
        }

        //
        // Step 2
        //
        if ((mState & GET_OR_CREATE_CONVERSATION) == 0) {
            mState |= GET_OR_CREATE_CONVERSATION;

            if (mGroup != null && mTwincodeOutboundId != null) {
                // For a group, we must not create the conversation and the get can fail.
                org.twinlife.twinlife.ConversationService.Conversation conversation = mTwinmeContext.getConversationService().getConversation(mGroup);
                if (conversation != null) {
                    onGetOrCreateConversation(conversation);
                } else {
                    // If the Group has no associated GroupConversation, it is invalid and must be removed.
                    mTwinmeContext.deleteGroup(mTwinmeContext.newRequestId(), mGroup);
                    mState |= GET_OR_CREATE_CONVERSATION_DONE;
                }
            } else if (mContact != null) {

                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationService.getOrCreateConversation: contact=" + mContact);
                }
                org.twinlife.twinlife.ConversationService.Conversation conversation = mTwinmeContext.getConversationService().getOrCreateConversation(mContact);
                if (conversation == null) {
                    onError(GET_OR_CREATE_CONVERSATION, BaseService.ErrorCode.ITEM_NOT_FOUND, null);
                    return;
                }
                onGetOrCreateConversation(conversation);
            }
            if ((mState & GET_OR_CREATE_CONVERSATION_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getConversationService().addServiceObserver(mResetConversationServiceObserver);
    }

    private void onGetContact(@NonNull BaseService.ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact contact=" + contact);
        }

        mState |= GET_CONTACT_DONE;
        mState |= GET_GROUP;
        mState |= GET_GROUP_DONE;

        mContact = contact;
        if (contact != null) {
            mTwincodeOutboundId = contact.getTwincodeOutboundId();
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetContact(contact, null);
                }
            });
        } else {
            onError(GET_CONTACT, errorCode, null);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetContactNotFound();
                }
            });
        }
        onOperation();
    }

    private void onGetGroup(@NonNull BaseService.ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroup group=" + group);
        }

        mState |= GET_GROUP_DONE;
        mState |= GET_CONTACT;
        mState |= GET_CONTACT_DONE;
        mState &= ~(GET_OR_CREATE_CONVERSATION | GET_OR_CREATE_CONVERSATION_DONE);

        mContact = group;
        mGroup = group;
        if (group != null) {
            mTwincodeOutboundId = group.getTwincodeOutboundId();
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroup(group);
                }
            });
        } else {
            onError(GET_GROUP, errorCode, null);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroupNotFound();
                }
            });
        }
        onOperation();
    }

    private void onGetOrCreateConversation(@NonNull org.twinlife.twinlife.ConversationService.Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetOrCreateConversation conversation=" + conversation);
        }

        mState |= GET_OR_CREATE_CONVERSATION_DONE;

        mConversation = conversation;
        mConversationId = conversation.getId();
    }

    private void onResetConversation(@NonNull org.twinlife.twinlife.ConversationService.Conversation conversation, @NonNull org.twinlife.twinlife.ConversationService.ClearMode clearMode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onResetConversation: conversation=" + conversation + " clearMode=" + clearMode);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onResetConversation(conversation, clearMode);
            }
        });
    }

    @Override
    protected void onError(int operationId, BaseService.ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == BaseService.ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        if (errorCode == BaseService.ErrorCode.ITEM_NOT_FOUND) {
            switch (operationId) {
                case GET_CONTACT:
                case GET_GROUP:
                    if (errorParameter != null) {
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(errorParameter);
                        } catch (Exception exception) {
                            uuid = null;
                        }

                        mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, mContactId, uuid);
                    }
                    return;

                default:
                    break;
            }
        }
        if (errorCode == BaseService.ErrorCode.NO_STORAGE_SPACE) {

            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
