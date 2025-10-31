/*
 *  Copyright (c) 2019-2025 twinlife SA.
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
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.DescriptorAnnotation;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InfoItemService extends AbstractTwinmeService {
    private static final String LOG_TAG = "InfoItemService";
    private static final boolean DEBUG = false;

    private static final int GET_CONTACT = 1;
    private static final int GET_GROUP = 2;
    private static final int GET_DESCRIPTOR = 4;
    private static final int GET_OR_CREATE_CONVERSATION = 1 << 5;
    private static final int LIST_GROUP_MEMBER = 1 << 6;
    private static final int LIST_GROUP_MEMBER_DONE = 1 << 7;
    private static final int UPDATE_DESCRIPTOR = 1 << 9;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver, GroupObserver {

        void onGetGroup(@NonNull Group group, @NonNull List<GroupMember> groupMembers,
                        @NonNull ConversationService.GroupConversation conversation, @Nullable Bitmap avatar);

        void onGetGroupMembers(@NonNull List<GroupMember> groupMembers);

        void onGetDescriptor(@Nullable Descriptor descriptor,
                             @Nullable Map<TwincodeOutbound, DescriptorAnnotation> annotations);
    }

    @NonNull
    private final DescriptorId mDescriptorId;
    @Nullable
    private final UUID mContactId;
    @Nullable
    private final UUID mGroupId;
    @Nullable
    private Originator mContact;
    @Nullable
    private Group mGroup;
    @Nullable
    private UUID mTwincodeOutboundId;
    private ConversationService.GroupConversation mGroupConversation;
    private List<GroupMember> mGroupMembers;

    private int mState = 0;
    @Nullable
    private Observer mObserver;

    public InfoItemService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                           @NonNull Observer observer, @Nullable UUID contactId, @Nullable UUID groupId,
                           @NonNull DescriptorId descriptorId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "InfoItemService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mDescriptorId = descriptorId;
        mContactId = contactId;
        mGroupId = groupId;

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

    public void listAnnotations(@NonNull DescriptorId descriptorId,
                                @NonNull TwinmeContext.ConsumerWithError<Map<TwincodeOutbound, DescriptorAnnotation>> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "listAnnotations: descriptorId=" + descriptorId);
        }

        mTwinmeContext.listAnnotations(descriptorId, consumer);
    }

    public void updateDescriptor(@NonNull DescriptorId descriptorId, boolean allowCopy) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDescriptor: descriptorId=" + descriptorId + " allowCopy=" + allowCopy);
        }

        mTwinmeContext.execute(() -> {
            long requestId = newOperation(UPDATE_DESCRIPTOR);
            mTwinmeContext.getConversationService().updateDescriptor(requestId, descriptorId, null, allowCopy, null);
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

        if (mContactId != null && (mState & GET_CONTACT) == 0) {
            mState |= GET_CONTACT;

            mTwinmeContext.getContact(mContactId, this::onGetContact);
            return;
        }

        if (mGroupId != null && (mState & GET_GROUP) == 0) {
            mState |= GET_GROUP;

            mTwinmeContext.getGroup(mGroupId, this::onGetGroup);
            return;
        }

        if (mGroup == null && mContact == null) {
            return;
        }

        //
        // Step 2
        //
        if ((mState & GET_OR_CREATE_CONVERSATION) == 0) {
            mState |= GET_OR_CREATE_CONVERSATION;

            if (mGroup != null && mTwincodeOutboundId != null) {
                // For a group, we must not create the conversation and the get can fail.
                ConversationService.Conversation conversation = mTwinmeContext.getConversationService().getConversation(mGroup);
                if (conversation != null) {
                    if (conversation instanceof ConversationService.GroupConversation) {
                        mGroupConversation = (ConversationService.GroupConversation) conversation;
                    }
                } else {
                    // If the Group has no associated GroupConversation, it is invalid and must be removed.
                    mTwinmeContext.deleteGroup(mTwinmeContext.newRequestId(), mGroup);
                    runOnGetContactNotFound(mObserver);
                }
            } else if (mContact != null) {

                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationService.getOrCreateConversation: contact=" + mContact);
                }
                ConversationService.Conversation conversation = mTwinmeContext.getConversationService().getOrCreateConversation(mContact);
                if (conversation == null) {
                    onError(GET_OR_CREATE_CONVERSATION, ErrorCode.ITEM_NOT_FOUND, null);
                    return;
                }
            }
        }

        //
        // Step 3: get the group members.
        //
        if (mGroupConversation != null && mGroup != null) {
            if ((mState & LIST_GROUP_MEMBER) == 0) {
                mState |= LIST_GROUP_MEMBER;

                mTwinmeContext.listGroupMembers(mGroup, ConversationService.MemberFilter.JOINED_MEMBERS, this::onListGroupMember);
                return;
            }
            if ((mState & LIST_GROUP_MEMBER_DONE) == 0) {
                return;
            }
        }

        if ((mState & GET_DESCRIPTOR) == 0) {
            mState |= GET_DESCRIPTOR;
            ConversationService conversationService = mTwinmeContext.getConversationService();
            Descriptor descriptor = conversationService.getDescriptor(mDescriptorId);
            Map<TwincodeOutbound, DescriptorAnnotation> annotations = conversationService.listAnnotations(mDescriptorId);
            if (descriptor != null && descriptor.getReplyToDescriptorId() != null) {
                descriptor.setReplyToDescriptor(conversationService.getDescriptor(descriptor.getReplyToDescriptorId()));
            }
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDescriptor(descriptor, annotations);
                }
            });
        }

        super.onOperation();
        hideProgressIndicator();
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact: contact=" + contact);
        }

        if (contact != null) {
            mContact = contact;
            mTwincodeOutboundId = contact.getTwincodeOutboundId();

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
            Log.d(LOG_TAG, "onGetGroup: group=" + group);
        }

        if (group != null) {
            mGroup = group;
            mTwincodeOutboundId = group.getTwincodeOutboundId();

            Bitmap avatar = getImage(group);
            runOnGetGroup(mObserver, group, avatar);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetGroupNotFound(mObserver);
        } else {
            onError(GET_GROUP, errorCode, null);
        }
        onOperation();
    }

    private void onListGroupMember(@NonNull ErrorCode errorCode, @Nullable List<GroupMember> list) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onListGroupMember: list=" + list);
        }

        if (errorCode != ErrorCode.SUCCESS || list == null) {
            onError(LIST_GROUP_MEMBER, errorCode, null);
            return;
        }

        mGroupMembers = list;
        mState |= LIST_GROUP_MEMBER_DONE;
        if (mGroup != null && mGroupConversation != null) {
            Bitmap avatar = getImage(mGroup);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroup(mGroup, mGroupMembers, mGroupConversation, avatar);
                }
            });

        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroupMembers(mGroupMembers);
                }
            });
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

        if (operationId == GET_CONTACT) {

            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnGetContactNotFound(mObserver);
                return;
            }
        } else if (operationId == GET_GROUP || operationId == LIST_GROUP_MEMBER) {

            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnGetGroupNotFound(mObserver);
                return;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
