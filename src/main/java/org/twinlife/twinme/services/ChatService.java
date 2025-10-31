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
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.GroupConversation;
import org.twinlife.twinlife.DisplayCallsMode;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChatService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ChatService";
    private static final boolean DEBUG = false;

    private static final int MAX_OBJECTS = 100;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_CONTACTS = 1 << 2;
    private static final int GET_CONTACTS_DONE = 1 << 3;
    private static final int GET_GROUPS = 1 << 4;
    private static final int GET_GROUPS_DONE = 1 << 5;
    private static final int GET_CONVERSATIONS = 1 << 6;
    private static final int GET_CONVERSATIONS_DONE = 1 << 7;
    private static final int GET_GROUP_MEMBER = 1 << 9;
    private static final int GET_GROUP_MEMBER_DONE = 1 << 10;

    public interface Observer extends AbstractTwinmeService.Observer, CurrentSpaceObserver, ContactListObserver,
            GroupListObserver, ContactObserver {

        void onCreateContact(@NonNull Contact contact, @Nullable Bitmap avatar);

        void onGetGroupMember(@Nullable UUID groupMemberTwincodeId, @Nullable GroupMember member);

        void onCreateGroup(@NonNull Group group, @NonNull ConversationService.GroupConversation conversation);

        void onJoinGroup(@NonNull ConversationService.GroupConversation conversation, @Nullable UUID memberId);

        void onLeaveGroup(@NonNull ConversationService.GroupConversation conversation, @Nullable UUID memberId);

        void onUpdateGroup(@NonNull Group group);

        void onDeleteGroup(@NonNull UUID contactId);

        void onGetConversations(@NonNull Map<ConversationService.Conversation, Descriptor> conversations);

        void onFindConversationsByName(@NonNull Map<ConversationService.Conversation, Descriptor> conversations);

        void onSearchDescriptors(@NonNull List<Pair<Conversation, Descriptor>> descriptors);

        void onGetOrCreateConversation(@NonNull ConversationService.Conversation conversation);

        void onResetConversation(@NonNull ConversationService.Conversation conversation, @NonNull ConversationService.ClearMode clearMode);

        void onDeleteConversation(@NonNull UUID conversationId);

        void onPushDescriptor(@NonNull Descriptor descriptor, @NonNull Conversation conversation);

        void onPopDescriptor(@NonNull Descriptor descriptor, @NonNull Conversation conversation);

        void onUpdateDescriptor(@NonNull Descriptor descriptor, @NonNull Conversation conversation);

        void onDeleteDescriptors(@NonNull Set<DescriptorId> descriptorList, @NonNull Conversation conversation);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            ChatService.this.onSetCurrentSpace(space);
        }

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            ChatService.this.onCreateContact(contact);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            ChatService.this.onUpdateContact(contact);
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Contact contact, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " contact=" + contact + " oldSpace=" + oldSpace);
            }

            ChatService.this.onMoveContact(contact);
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Group group, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " group=" + group + " oldSpace=" + oldSpace);
            }

            ChatService.this.onMoveGroup(group);
        }

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            ChatService.this.onDeleteContact(contactId);
        }

        @Override
        public void onCreateGroup(long requestId, @NonNull Group group, @NonNull ConversationService.GroupConversation conversation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateGroup: requestId=" + requestId + " group=" + group);
            }

            ChatService.this.onCreateGroup(group, conversation);
        }

        @Override
        public void onUpdateGroup(long requestId, @NonNull Group group) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateGroup: requestId=" + requestId + " group=" + group);
            }

            ChatService.this.onUpdateGroup(group);
        }

        @Override
        public void onDeleteGroup(long requestId, @NonNull UUID groupId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteGroup: requestId=" + requestId + " groupId=" + groupId);
            }

            ChatService.this.onDeleteGroup(groupId);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onCreateConversation(@NonNull ConversationService.Conversation conversation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onCreateConversation: conversation=" + conversation);
            }

            ChatService.this.onGetOrCreateConversation(conversation);
        }

        @Override
        public void onPushDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPushDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            ChatService.this.onPushDescriptor(descriptor, conversation);
        }

        @Override
        public void onPopDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPopDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            ChatService.this.onPopDescriptor(descriptor, conversation);
        }

        @Override
        public void onUpdateDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor, ConversationService.UpdateType updateType) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onUpdateDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor + " updateType=" + updateType);
            }

            if (updateType == ConversationService.UpdateType.CONTENT && descriptor.getType() != Descriptor.Type.OBJECT_DESCRIPTOR) {

                return;
            }

            ChatService.this.onUpdateDescriptor(descriptor, conversation);
        }

        @Override
        public void onMarkDescriptorRead(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onMarkDescriptorRead: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            ChatService.this.onUpdateDescriptor(descriptor, conversation);
        }

        @Override
        public void onDeleteDescriptors(long requestId, @NonNull Conversation conversation, @NonNull DescriptorId[] descriptorList) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptorList=" + descriptorList.length);
            }

            ChatService.this.onDeleteDescriptors(descriptorList, conversation);
        }

        @Override
        public void onResetConversation(@NonNull Conversation conversation, @NonNull ConversationService.ClearMode clearMode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onResetConversation: conversation=" + conversation);
            }

            ChatService.this.onResetConversation(conversation, clearMode);
        }

        @Override
        public void onDeleteConversation(@NonNull UUID conversationId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteConversation: conversationId=" + conversationId);
            }

            // A group conversation can be deleted when a user leaves or is removed from the group.
            ChatService.this.onDeleteConversation(conversationId);
        }

        @Override
        public void onDeleteGroupConversation(@NonNull UUID conversationId, @NonNull UUID groupId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteGroupConversation: conversationId="
                        + conversationId + " groupId=" + groupId);
            }

            // A group conversation can be deleted when a user leaves or is removed from the group.
            ChatService.this.onDeleteConversation(conversationId);
        }

        @Override
        public void onJoinGroupRequest(long requestId, @NonNull GroupConversation conversation, @Nullable ConversationService.InvitationDescriptor invitation, @NonNull UUID memberId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupRequest: requestId=" + requestId + " conversation=" + conversation);
            }

            ChatService.this.onJoinGroup(conversation, memberId);
        }

        @Override
        public void onJoinGroupResponse(long requestId, @NonNull GroupConversation conversation, @Nullable ConversationService.InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupResponse: requestId=" + requestId + " conversation=" + conversation);
            }

            ChatService.this.onJoinGroup(conversation, null);
        }

        @Override
        public void onLeaveGroup(long requestId, @NonNull GroupConversation conversation, @NonNull UUID memberTwincodeId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onLeaveGroup: requestId=" + requestId
                        + " conversation=" + conversation + " memberTwincodeId=" + memberTwincodeId);
            }

            // The group conversation state can change to LEAVING and we want to hide it.
            ChatService.this.onLeaveGroup(conversation, memberTwincodeId);
        }

        @Override
        public void onError(long requestId, BaseService.ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            ChatService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    static final class GroupMemberQuery {
        final Group mGroup;
        final UUID mMemberTwincodeOutboundId;

        GroupMemberQuery(Group group, UUID memberTwincodeOutboundId) {
            mGroup = group;
            mMemberTwincodeOutboundId = memberTwincodeOutboundId;
        }
    }

    @Nullable
    private ChatService.Observer mObserver;
    private int mState = 0;
    private final ConversationServiceObserver mConversationServiceObserver;
    private final List<GroupMemberQuery> mGroupMemberConversations = new ArrayList<>();
    private final List<Conversation> mConversations = new ArrayList<>();
    private final Set<UUID> mOriginatorIds = new HashSet<>();
    private GroupMemberQuery mCurrentGroupMember;
    @Nullable
    private String mFindName;
    @Nullable
    private Space mSpace;
    @NonNull
    private final DisplayCallsMode mCallsMode;

    private long mBeforeTimestamp = Long.MAX_VALUE;
    private boolean mGetDescriptorsDone = false;

    public ChatService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                       @NonNull DisplayCallsMode callsMode, @NonNull ChatService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ContactsService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new ChatService.TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mCallsMode = callsMode;
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getConversationService().removeServiceObserver(mConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    public void findConversationsByName(@NonNull String name) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactsByName: name=" + name);
        }

        mFindName = normalize(name);

        mState &= ~(GET_CONVERSATIONS | GET_CONVERSATIONS_DONE);
        startOperation();
    }

    public void searchDescriptorsByContent(@NonNull String content, boolean clear) {
        if (DEBUG) {
            Log.d(LOG_TAG, "searchDescriptorsByContent: content=" + content);
        }

        if (clear) {
            mGetDescriptorsDone = false;
            mBeforeTimestamp = Long.MAX_VALUE;
        }

        mTwinmeContext.execute(() -> {
            List<Pair<Conversation, Descriptor>> descriptors = mTwinmeContext.getConversationService().searchDescriptors(mConversations, content, mBeforeTimestamp, MAX_OBJECTS);

            if (descriptors != null && descriptors.size() < MAX_OBJECTS) {
                mGetDescriptorsDone = true;
            }

            if (descriptors != null && !descriptors.isEmpty()) {
                Pair<Conversation, Descriptor> descriptorPair = descriptors.get(descriptors.size() - 1);
                Descriptor descriptor = descriptorPair.second;
                mBeforeTimestamp = descriptor.getCreatedTimestamp();
            }

            runOnUiThread(() -> {
                if (mObserver != null && descriptors != null) {
                    mObserver.onSearchDescriptors(descriptors);
                }
            });
        });
    }

    public boolean isGetDescriptorsDone() {
        if (DEBUG) {
            Log.d(LOG_TAG, "DescriptorsDone");
        }

        return mGetDescriptorsDone;
    }

    public void getLastDescriptor(@NonNull Conversation conversation, @NonNull Consumer<Descriptor> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getLastDescriptor: subject=" + conversation);
        }

        mTwinmeContext.execute(() -> {
            ConversationService conversationService = mTwinmeContext.getConversationService();
            List<Descriptor> descriptors = conversationService.getConversationDescriptors(conversation, mCallsMode, Long.MAX_VALUE, 1);

            runOnUiThread(() -> {
                if (descriptors == null || descriptors.isEmpty()) {
                    consumer.onGet(ErrorCode.ITEM_NOT_FOUND, null);
                } else {
                    consumer.onGet(ErrorCode.SUCCESS, descriptors.get(0));
                }
            });
        });
    }

    public void getGroupMembers(@NonNull Group group, @NonNull List<UUID> members) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroupMembers group=" + group);
        }

        for (UUID member : members) {
            mGroupMemberConversations.add(new GroupMemberQuery(group, member));
        }
        if ((mState & GET_GROUP_MEMBER_DONE) != 0) {
            mState &= ~(GET_GROUP_MEMBER | GET_GROUP_MEMBER_DONE);
        }
        if (mCurrentGroupMember == null) {
            nextGroupMember();
        }
        startOperation();
    }

    public void resetConversation(@NonNull RepositoryObject subject) {
        if (DEBUG) {
            Log.d(LOG_TAG, "resetConversation subject=" + subject);
        }

        mTwinmeContext.execute(() -> {
            // Clear both sides of conversation (ignore any error).
            ConversationService conversationService = mTwinmeContext.getConversationService();
            Conversation conversation = conversationService.getConversation(subject);
            if (conversation != null) {
                conversationService.clearConversation(conversation, System.currentTimeMillis(), ConversationService.ClearMode.CLEAR_BOTH);
            }
        });
    }

    private void nextGroupMember() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextGroupMember");
        }

        if (mGroupMemberConversations.isEmpty()) {
            mState |= GET_GROUP_MEMBER | GET_GROUP_MEMBER_DONE;
            hideProgressIndicator();

        } else {
            mCurrentGroupMember = mGroupMemberConversations.remove(0);
            mState &= ~(GET_GROUP_MEMBER | GET_GROUP_MEMBER_DONE);
        }
    }

    private void onCreateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateContact: contact=" + contact);
        }

        if (contact.getSpace() == mSpace) {
            mOriginatorIds.add(contact.getId());
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
            mOriginatorIds.remove(contact.getId());
            runOnDeleteContact(mObserver, contact.getId());
        }
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        mOriginatorIds.remove(contactId);
        runOnDeleteContact(mObserver, contactId);
    }

    private void onCreateGroup(@NonNull Group group, @NonNull GroupConversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateGroup: group=" + group);
        }

        if (group.getSpace() == mSpace) {
            mOriginatorIds.add(group.getId());

            if (!mConversations.contains(conversation)) {
                mConversations.add(conversation);
            }

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onCreateGroup(group, conversation);
                }
            });
        }
    }

    private void onUpdateGroup(@NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateGroup: group=" + group);
        }

        if (group.getSpace() == mSpace) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onUpdateGroup(group);
                }
            });
        }
    }

    private void onMoveGroup(@NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveGroup: group=" + group);
        }

        if (group.getSpace() != mSpace) {
            mOriginatorIds.add(group.getId());
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onDeleteGroup(group.getId());
                }
            });
        }
    }

    private void onDeleteGroup(@NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteGroup: groupId=" + groupId);
        }

        mOriginatorIds.remove(groupId);
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteGroup(groupId);
            }
        });
    }

    private void onGetGroupMember(@NonNull ErrorCode errorCode, @Nullable GroupMember member) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroupMember: member=" + member);
        }

        if (errorCode != ErrorCode.SUCCESS || member == null) {

            onError(GET_GROUP_MEMBER, errorCode, null);
            return;
        }

        mState |= GET_GROUP_MEMBER_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetGroupMember(member.getPeerTwincodeOutboundId(), member);
            }
        });
        nextGroupMember();
        onOperation();
    }

    private void onDeleteConversation(@NonNull UUID conversationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteConversation: conversationId=" + conversationId);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteConversation(conversationId);
            }
        });
    }

    private void onGetOrCreateConversation(@NonNull Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetOrCreateConversation: conversation=" + conversation);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {

            if (!mConversations.contains(conversation)) {
                mConversations.add(conversation);
            }

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetOrCreateConversation(conversation);
                }
            });
        }
    }

    private void onPushDescriptor(@NonNull Descriptor descriptor, @NonNull Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPushDescriptor: descriptor=" + descriptor + "conversation=" + conversation);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {

            if (!mConversations.contains(conversation)) {
                mConversations.add(conversation);
            }

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onPushDescriptor(descriptor, conversation);
                }
            });
        }
    }

    private void onPopDescriptor(@NonNull Descriptor descriptor, @NonNull Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPopDescriptor: descriptor=" + descriptor + "conversation=" + conversation);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {

            if (!mConversations.contains(conversation)) {
                mConversations.add(conversation);
            }

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onPopDescriptor(descriptor, conversation);
                }
            });
        }
    }

    private void onUpdateDescriptor(@NonNull Descriptor descriptor, @NonNull Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateDescriptor: descriptor=" + descriptor + "conversation=" + conversation);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {

            if (!mConversations.contains(conversation)) {
                mConversations.add(conversation);
            }

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onUpdateDescriptor(descriptor, conversation);
                }
            });
        }
    }

    private void onDeleteDescriptors(@NonNull DescriptorId[] descriptorList, @NonNull Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor: descriptorList=" + descriptorList.length + "conversation=" + conversation);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {

            Set<DescriptorId> descriptorIdSet = new HashSet<>(Arrays.asList(descriptorList));
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onDeleteDescriptors(descriptorIdSet, conversation);
                }
            });
        }
    }

    private void onJoinGroup(@NonNull GroupConversation conversation, @Nullable UUID memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onJoinGroup: conversation=" + conversation);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onJoinGroup(conversation, memberId);
                }
            });
        }
    }

    private void onLeaveGroup(@NonNull GroupConversation conversation, @NonNull UUID memberTwincodeId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onLeaveGroup: conversation=" + conversation + " memberTwincodeId=" + memberTwincodeId);
        }

        // The user has left the group, remove it from the UI.
        if (mOriginatorIds.contains(conversation.getContactId())) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    if (conversation.getState() != ConversationService.GroupConversation.State.JOINED) {
                        mObserver.onDeleteConversation(conversation.getId());
                    } else {
                        mObserver.onLeaveGroup(conversation, memberTwincodeId);
                    }
                }
            });
        }
    }

    private void onResetConversation(@NonNull Conversation conversation, @NonNull ConversationService.ClearMode clearMode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onResetConversation: conversation=" + conversation + " clearMode=" + clearMode);
        }

        if (mOriginatorIds.contains(conversation.getContactId())) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onResetConversation(conversation, clearMode);
                }
            });
        }
    }

    private void onGetConversations(Map<Conversation, Descriptor> conversations) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetConversations: conversation=" + conversations);
        }

        mConversations.clear();

        for (Map.Entry<Conversation, Descriptor> item : conversations.entrySet()) {
            mConversations.add(item.getKey());
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetConversations(conversations);
            }
        });
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        if (mSpace != space) {
            mFindName = null;
            mSpace = space;
            mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE);
            mState &= ~(GET_GROUPS | GET_GROUPS_DONE);
            mState &= ~(GET_CONVERSATIONS | GET_CONVERSATIONS_DONE);
            mOriginatorIds.clear();
            mCurrentGroupMember = null;
        }
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
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
                mOriginatorIds.clear();
                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2: Get the list of contacts.
        //
        if ((mState & GET_CONTACTS) == 0) {
            mState |= GET_CONTACTS;
            final Filter<RepositoryObject> filter = mTwinmeContext.createSpaceFilter();
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.findContacts: filter=" + filter);
            }

            // TwinmeContext.Predicate<Contact> filter = (Contact contact) -> (contact.getSpace() == mSpace);
            mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
                runOnGetContacts(mObserver, contacts);
                for (Contact contact : contacts) {
                    mOriginatorIds.add(contact.getId());
                }
                mState |= GET_CONTACTS_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CONTACTS_DONE) == 0) {
            return;
        }

        //
        // Step 3: get the list of groups before the conversations.
        //
        if ((mState & GET_GROUPS) == 0) {
            mState |= GET_GROUPS;
            final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                public boolean accept(@NonNull RepositoryObject object) {

                    if (!(object instanceof Group)) {
                        return false;
                    }
                    Group group = (Group) object;
                    return !group.isLeaving();
                }
            };
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.findGroups: filter=" + filter);
            }

            mTwinmeContext.findGroups(filter, (List<Group> groups) -> {
                runOnGetGroups(mObserver, groups);
                for (Group group : groups) {
                    mOriginatorIds.add(group.getId());
                }
                mState |= GET_GROUPS_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_GROUPS_DONE) == 0) {
            return;
        }

        //
        // Step 4: get the list of conversation (will be sorted on object's usage).
        //
        if ((mState & GET_CONVERSATIONS) == 0) {
            mState |= GET_CONVERSATIONS;

            Filter<Conversation> filter;
            if (mFindName == null) {
                filter = new Filter<>(mSpace);
            } else {
                filter = new Filter<Conversation>(mSpace) {
                    public boolean accept(@NonNull Conversation conversation) {
                        String contactName = normalize(conversation.getSubject().getName());
                        return contactName.contains(mFindName);
                    }
                };
            }

            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.findConversations: filter=" + filter);
            }

            mTwinmeContext.findConversationDescriptors(filter, mCallsMode, (Map<Conversation, Descriptor> conversations) -> {

                if (mFindName != null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onFindConversationsByName(conversations);
                        }
                    });
                } else {
                    onGetConversations(conversations);
                }


                mState |= GET_CONVERSATIONS_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CONVERSATIONS_DONE) == 0) {
            return;
        }

        //
        // Step 5: get the group members (each of them, one by one until we are done).
        //
        if (mCurrentGroupMember != null) {
            if ((mState & GET_GROUP_MEMBER) == 0) {
                mState |= GET_GROUP_MEMBER;

                mTwinmeContext.getGroupMember(mCurrentGroupMember.mGroup, mCurrentGroupMember.mMemberTwincodeOutboundId,
                        this::onGetGroupMember);
                return;
            }
            if ((mState & GET_GROUP_MEMBER_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step
        //
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

        if (errorCode == ErrorCode.ITEM_NOT_FOUND  && operationId == GET_GROUP_MEMBER) {
            mState |= GET_GROUP_MEMBER_DONE;
            nextGroupMember();
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
