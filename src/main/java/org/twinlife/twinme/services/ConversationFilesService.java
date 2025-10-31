/*
 *  Copyright (c) 2023-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.DisplayCallsMode;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ConversationFilesService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ConversationFiles...";
    private static final boolean DEBUG = false;

    private static final int MAX_OBJECTS = 64;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;
    private static final int GET_GROUP = 1 << 2;
    private static final int GET_GROUP_DONE = 1 << 3;
    private static final int GET_OR_CREATE_CONVERSATION = 1 << 4;
    private static final int GET_OR_CREATE_CONVERSATION_DONE = 1 << 5;
    private static final int GET_DESCRIPTORS = 1 << 6;
    private static final int GET_DESCRIPTORS_DONE = 1 << 7;
    private static final int MARK_DESCRIPTOR_DELETED = 1 << 8;
    private static final int DELETE_DESCRIPTOR = 1 << 9;

    public interface Observer extends AbstractTwinmeService.Observer, AbstractTwinmeService.ContactObserver, AbstractTwinmeService.GroupObserver {

        void onGetDescriptors(@NonNull List<Descriptor> descriptors);

        void onMarkDescriptorDeleted(@NonNull Descriptor descriptor);

        void onDeleteDescriptors(@NonNull Set<DescriptorId> descriptorList);
    }

    private class ConversationFilesServiceObserver extends org.twinlife.twinlife.ConversationService.DefaultServiceObserver {

        @Override
        public void onMarkDescriptorDeleted(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationFilesServiceObserver.onMarkDescriptorDeleted: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationFilesService.this.onMarkDescriptorDeleted(descriptor);
        }

        @Override
        public void onDeleteDescriptors(long requestId, @NonNull Conversation conversation, @NonNull DescriptorId[] descriptorList) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationFilesServiceObserver.onDeleteDescriptors: requestId=" + requestId + " conversation=" + conversation);
            }

            if (!isConversation(conversation)) {
                return;
            }

            ConversationFilesService.this.onDeleteDescriptors(descriptorList);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationFilesServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            if (requestId == BaseService.DEFAULT_REQUEST_ID) {
                if (errorCode == ErrorCode.FEATURE_NOT_SUPPORTED_BY_PEER) {
                    UUID conversationId;
                    try {
                        conversationId = UUID.fromString(errorParameter);
                    } catch (Exception exception) {
                        conversationId = null;
                    }
                    if (conversationId != null && mConversation != null
                            && conversationId.equals(ConversationFilesService.this.mConversation.getId())) {
                        ConversationFilesService.this.onConversationError(errorCode, errorParameter);
                        onOperation();
                    }
                } else {
                    mTwinmeContext.assertion(ServiceAssertPoint.ON_ERROR, AssertPoint.create(getClass()).put(errorCode));
                }
            } else {
                Integer operationId = getOperation(requestId);
                if (operationId == null) {

                    return;
                }

                ConversationFilesService.this.onError(operationId, errorCode, errorParameter);
                onOperation();
            }
        }
    }

    @Nullable
    private Observer mObserver;
    @Nullable
    private final UUID mContactId;
    @Nullable
    private Originator mContact;
    @Nullable
    private final UUID mGroupId;
    @Nullable
    private Group mGroup;
    private UUID mTwincodeOutboundId;
    private UUID mPeerTwincodeOutboundId;
    private UUID mTwincodeInboundId;
    private boolean mIsGroup = false;
    @Nullable
    private Conversation mConversation;
    private UUID mConversationId;
    private long mBeforeTimestamp = Long.MAX_VALUE;
    private boolean mGetDescriptorsDone = false;
    private int mState = 0;
    private final ConversationFilesServiceObserver mConversationFilesServiceObserver;
    private final String mDescriptorIds;

    public ConversationFilesService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                    @NonNull Observer observer, @Nullable String descriptorIds,
                                    @Nullable UUID contactId, @Nullable UUID groupId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ConversationFilesService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mDescriptorIds = descriptorIds;
        mContactId = contactId;
        mGroupId = groupId;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationFilesServiceObserver = new ConversationFilesServiceObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public boolean isLocalDescriptor(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isLocalDescriptor: descriptor=" + descriptor);
        }

        return descriptor.getTwincodeOutboundId().equals(mTwincodeOutboundId);
    }

    public boolean isPeerDescriptor(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isPeerDescriptor: descriptor=" + descriptor);
        }

        return mPeerTwincodeOutboundId == null || descriptor.getTwincodeOutboundId().equals(mPeerTwincodeOutboundId)
                || mIsGroup;
    }

    public void getPreviousObjectDescriptors() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPreviousObjectDescriptors");
        }

        if (mGetDescriptorsDone) {

            return;
        }

        if ((mState & GET_DESCRIPTORS) != 0 && (mState & GET_DESCRIPTORS_DONE) != 0) {
            mState &= ~GET_DESCRIPTORS;
            mState &= ~GET_DESCRIPTORS_DONE;

            startOperation();
        }
    }

    public void markDescriptorDeleted(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "markDescriptorDeleted: descriptorId=" + descriptorId);
        }

        long requestId = newOperation(MARK_DESCRIPTOR_DELETED);
        if (DEBUG) {
            Log.d(LOG_TAG, "ConversationFilesService.markDescriptorDeleted: requestId=" + requestId + " descriptorId=" + descriptorId);
        }
        mTwinmeContext.markDescriptorDeleted(requestId, descriptorId);
    }

    public void deleteDescriptor(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteDescriptor: descriptorId=" + descriptorId);
        }

        long requestId = newOperation(DELETE_DESCRIPTOR);
        if (DEBUG) {
            Log.d(LOG_TAG, "ConversationFilesService.deleteDescriptor: requestId=" + requestId + " descriptorId=" + descriptorId);
        }
        mTwinmeContext.deleteDescriptor(requestId, descriptorId);
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getConversationService().removeServiceObserver(mConversationFilesServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    //
    // Private methods
    //

    private boolean isConversation(@Nullable Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isConversation conversation=" + conversation);
        }

        // For some reason, we can have a null conversation object.
        return conversation != null && mConversationId != null && conversation.isConversation(mConversationId);
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

        if (mGroupId != null) {
            if ((mState & GET_GROUP) == 0) {
                mState |= GET_GROUP;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.getGroup: group=" + mGroupId);
                }
                mTwinmeContext.getGroup(mGroupId, this::onGetGroup);
                return;
            }
            if ((mState & GET_GROUP_DONE) == 0) {
                return;
            }
        }

        //
        // Step 2
        //

        if (mGroup != null && mTwincodeOutboundId != null) {
            // For a group, we must not create the conversation and the get can fail.
            Conversation conversation = mTwinmeContext.getConversationService().getConversation(mGroup);
            if (conversation != null) {
                onGetOrCreateConversation(conversation);
            }
        } else if (mContact != null && mTwincodeOutboundId != null) {
            if ((mState & GET_OR_CREATE_CONVERSATION) == 0) {
                mState |= GET_OR_CREATE_CONVERSATION;

                mTwinmeContext.assertNotNull(ServiceAssertPoint.NULL_SUBJECT, mContact, 319);

                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationFilesService.getConversation: twincodeOutboundId=" + mTwincodeOutboundId + " peerTwincodeOutboundId=" +
                            mPeerTwincodeOutboundId + " twincodeInboundId=" + mTwincodeInboundId + " contactId=" + mContact.getId());
                }
                Conversation conversation = mTwinmeContext.getConversationService().getOrCreateConversation(mContact);
                if (conversation == null) {
                    onError(GET_OR_CREATE_CONVERSATION, ErrorCode.ITEM_NOT_FOUND, null);
                    return;
                }
                onGetOrCreateConversation(conversation);
            }
            if ((mState & GET_OR_CREATE_CONVERSATION_DONE) == 0) {
                return;
            }
        }

        //
        // Step 3
        //
        if ((mDescriptorIds != null && (mState & GET_DESCRIPTORS) == 0)) {
            mState |= GET_DESCRIPTORS;

            String[] descriptorIds = mDescriptorIds.split(",");
            final List<Descriptor> descriptors = new ArrayList<>();
            for (String item : descriptorIds) {
                DescriptorId descriptorId = DescriptorId.fromString(item);
                if (descriptorId != null) {
                    Descriptor descriptor = mTwinmeContext.getConversationService().getDescriptor(descriptorId);
                    if (descriptor != null) {
                        descriptors.add(descriptor);
                    }
                }
            }
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDescriptors(descriptors);
                }
            });
        }

        if (mConversation != null) {
            if ((mState & GET_DESCRIPTORS) == 0) {
                mState |= GET_DESCRIPTORS;

                long requestId = newOperation(GET_DESCRIPTORS);
                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationFilesService.getDescriptors: requestId=" + requestId + " mConversation=" + mConversation +
                            " mBeforeTimestamp=" + mBeforeTimestamp + " max=" + MAX_OBJECTS);
                }
                Descriptor.Type[] types = new Descriptor.Type[] { Descriptor.Type.IMAGE_DESCRIPTOR, Descriptor.Type.NAMED_FILE_DESCRIPTOR, Descriptor.Type.OBJECT_DESCRIPTOR, Descriptor.Type.VIDEO_DESCRIPTOR};
                List<Descriptor> descriptors = mTwinmeContext.getConversationService().getConversationTypeDescriptors(mConversation, types, DisplayCallsMode.ALL, mBeforeTimestamp, MAX_OBJECTS);
                if (descriptors != null) {
                    onGetDescriptors(descriptors);
                } else {
                    mState |= GET_DESCRIPTORS_DONE;
                }
            }
            if ((mState & GET_DESCRIPTORS_DONE) == 0) {
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

        mTwinmeContext.getConversationService().addServiceObserver(mConversationFilesServiceObserver);
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact contact=" + contact);
        }

        mState |= GET_CONTACT_DONE;
        mState |= GET_GROUP;
        mState |= GET_GROUP_DONE;

        mContact = contact;
        if (contact != null) {
            mTwincodeOutboundId = contact.getTwincodeOutboundId();
            mTwincodeInboundId = contact.getTwincodeInboundId();
            mPeerTwincodeOutboundId = contact.getPeerTwincodeOutboundId();
            mIsGroup = contact.isTwinroom();

            Bitmap avatar = getImage(contact);
            runOnGetContact(mObserver, contact, avatar);
            if (avatar == null && contact.getAvatarId() != null) {
                getImageFromServer(contact);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_CONTACT, errorCode, null);
        }
        onOperation();
    }

    private void onGetGroup(@NonNull ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetProfileGroup group=" + group);
        }

        mState |= GET_GROUP_DONE;
        mState |= GET_CONTACT;
        mState |= GET_CONTACT_DONE;
        mState &= ~(GET_OR_CREATE_CONVERSATION | GET_OR_CREATE_CONVERSATION_DONE);

        mContact = group;
        mGroup = group;
        mIsGroup = true;
        if (group != null) {
            mTwincodeOutboundId = group.getTwincodeOutboundId();
            mTwincodeInboundId = group.getTwincodeInboundId();
            mPeerTwincodeOutboundId = group.getGroupTwincodeOutboundId();

            Bitmap avatar = getImage(group);
            runOnGetGroup(mObserver, group, avatar);
            if (avatar == null && group.getAvatarId() != null) {
                getImageFromServer(group);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetGroupNotFound(mObserver);
        } else {
            onError(GET_GROUP, errorCode, null);
        }
        onOperation();
    }

    private void onGetOrCreateConversation(@NonNull Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetOrCreateConversation conversation=" + conversation);
        }

        mState |= GET_OR_CREATE_CONVERSATION_DONE;

        mConversation = conversation;
        mConversationId = mConversation.getId();
    }

    private void onGetDescriptors(@NonNull List<Descriptor> descriptors) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetDescriptors: descriptors=" + descriptors);
        }

        mState |= GET_DESCRIPTORS_DONE;

        for (Descriptor descriptor : descriptors) {
            if (descriptor.getCreatedTimestamp() < mBeforeTimestamp) {
                mBeforeTimestamp = descriptor.getCreatedTimestamp();
            }
        }

        if (descriptors.size() < MAX_OBJECTS) {
            mGetDescriptorsDone = true;
        }
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetDescriptors(descriptors);
            }
        });
    }

    private void onMarkDescriptorDeleted(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMarkDescriptorDeleted: descriptor=" + descriptor);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onMarkDescriptorDeleted(descriptor);
            }
        });
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

        if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            switch (operationId) {
                case GET_CONTACT:
                case GET_GROUP:
                    runOnGetContactNotFound(mObserver);
                    return;

                case MARK_DESCRIPTOR_DELETED:
                case DELETE_DESCRIPTOR:

                    return;

                default:
                    break;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }

    private void onConversationError(ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConversationError: errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

    }

    private void onDeleteDescriptors(@NonNull DescriptorId[] descriptorList) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor: descriptor=" + descriptorList.length);
        }

        Set<DescriptorId> descriptorIdSet = new HashSet<>();
        Collections.addAll(descriptorIdSet, descriptorList);
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteDescriptors(descriptorIdSet);
            }
        });
    }
}
