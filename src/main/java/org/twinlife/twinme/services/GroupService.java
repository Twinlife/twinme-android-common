/*
 *  Copyright (c) 2018-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.GroupConversation;
import org.twinlife.twinlife.ConversationService.InvitationDescriptor;
import org.twinlife.twinlife.ConversationService.MemberFilter;
import org.twinlife.twinlife.ConversationService.UpdateType;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Capabilities;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Invitation;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Group service to retreive group information and perform group management operations.
 */
public class GroupService extends AbstractTwinmeService {
    private static final String LOG_TAG = "GroupService";
    private static final boolean DEBUG = false;

    private static final int GET_GROUP = 1;
    private static final int GET_GROUP_DONE = 1 << 1;
    private static final int GET_CONTACT = 1 << 2;
    private static final int GET_CONTACT_DONE = 1 << 3;
    private static final int GET_CONTACTS = 1 << 4;
    private static final int GET_CONTACTS_DONE = 1 << 5;
    private static final int CREATE_GROUP = 1 << 6;
    private static final int CREATE_GROUP_DONE = 1 << 7;
    private static final int LIST_GROUP_MEMBER = 1 << 8;
    private static final int LIST_GROUP_MEMBER_DONE = 1 << 9;
    private static final int INVITE_GROUP_MEMBER = 1 << 10;
    private static final int INVITE_GROUP_MEMBER_DONE = 1 << 11;
    private static final int UPDATE_GROUP = 1 << 13;
    private static final int UPDATE_GROUP_DONE = 1 << 14;

    private static final int DELETE_GROUP = 1 << 15;
    private static final int DELETE_GROUP_DONE = 1 << 16;
    private static final int MEMBER_LEAVE_GROUP = 1 << 17;

    private static final int GET_PENDING_INVITATIONS = 1 << 18;

    private static final int CREATE_INVITATION = 1 << 19;
    private static final int CREATE_INVITATION_DONE = 1 << 20;

    private static final int GET_SPACE = 1 << 23;
    private static final int GET_SPACE_DONE = 1 << 24;

    private static final int FIND_CONTACTS = 1 << 27;
    private static final int FIND_CONTACTS_DONE = 1 << 28;

    private static final int GET_GROUP_IMAGE = 1 << 29;
    private static final int GET_GROUP_IMAGE_DONE = 1 << 30;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver,
            SpaceObserver, CurrentSpaceObserver, ContactListObserver {

        void onCreateGroup(@NonNull Group group, @NonNull GroupConversation conversation);

        void onGetGroup(@NonNull Group group, @NonNull List<GroupMember> groupMembers, @NonNull GroupConversation conversation);

        void onUpdateGroup(@NonNull Group group, @Nullable Bitmap avatar);

        void onInviteGroup(@NonNull Conversation conversation, @NonNull InvitationDescriptor invitationDescriptor);

        void onLeaveGroup(@NonNull Group group, @NonNull UUID memberTwincodeId);

        void onDeleteGroup(UUID groupId);

        void onCreateInvitation(@NonNull Invitation invitation);

        void onGetCurrentSpace(@NonNull Space space);

        void onGetGroupNotFound();

        void onErrorLimitReached();
    }

    public interface PendingInvitationsObserver {

        void onListPendingInvitations(@NonNull Map<UUID, InvitationDescriptor> list);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateGroup(long requestId, @NonNull Group group, @NonNull GroupConversation conversation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateGroup: requestId=" + requestId + " group=" + group);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            GroupService.this.onCreateGroup(group, conversation);
        }

        @Override
        public void onUpdateGroup(long requestId, @NonNull Group group) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateGroup: requestId=" + requestId + " group=" + group);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            GroupService.this.onUpdateGroup(group);
        }

        @Override
        public void onDeleteGroup(long requestId, @NonNull UUID groupId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteGroup: requestId=" + requestId + " groupId=" + groupId);
            }

            GroupService.this.onDeleteGroup(groupId);
        }

        @Override
        public void onCreateInvitation(long requestId, @NonNull Invitation invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateInvitation: requestId=" + requestId + " invitation=" + invitation);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            GroupService.this.onCreateInvitation(invitation);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            GroupService.this.onSetCurrentSpace(space);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onInviteGroup(long requestId, @NonNull Conversation conversation, @NonNull InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onInviteGroup: requestId=" + requestId + " conversation=" + conversation);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            GroupService.this.onInviteGroup(conversation, invitation);
        }

        @Override
        public void onUpdateDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor invitation, UpdateType updateType) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onInviteGroupUpdate: requestId=" + requestId + " conversation=" + conversation);
            }

            if (invitation instanceof InvitationDescriptor) {
                GroupService.this.onUpdateInvitation(conversation, (InvitationDescriptor) invitation);
            }
        }

        @Override
        public void onJoinGroup(long requestId, @Nullable GroupConversation conversation, @NonNull InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroup: requestId=" + requestId + " conversation=" + conversation);
            }

            // The GroupService does not make joinGroup() calls but wants to be informed about new members.
            GroupService.this.onJoinGroup(conversation, invitation, invitation.getMemberTwincodeId());
        }

        @Override
        public void onJoinGroupResponse(long requestId, @Nullable GroupConversation conversation, @Nullable InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupResponse: requestId=" + requestId + " conversation=" + conversation);
            }

            if (invitation == null || conversation == null) {

                return;
            }

            GroupService.this.onJoinGroup(conversation, invitation, invitation.getMemberTwincodeId());
        }

        @Override
        public void onJoinGroupRequest(long requestId, @NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation, @NonNull UUID memberId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupRequest: requestId=" + requestId + " conversation=" + conversation);
            }

            GroupService.this.onJoinGroup(conversation, invitation, memberId);
        }

        @Override
        public void onLeaveGroup(long requestId, @NonNull GroupConversation conversation, @NonNull UUID memberTwincodeId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onLeaveGroup: requestId=" + requestId
                        + " conversation=" + conversation + " memberTwincodeId=" + memberTwincodeId);
            }

            getOperation(requestId);

            GroupService.this.onLeaveGroup(conversation, memberTwincodeId);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            GroupService.this.onError(operationId, errorCode, errorParameter);
        }
    }

    @Nullable
    private Observer mObserver;
    private UUID mGroupId;
    private UUID mContactId;
    @NonNull
    private final ConversationServiceObserver mConversationServiceObserver;
    private ConversationService mConversationService;
    private int mState = 0;
    private int mWork = 0;
    private String mGroupName;
    @Nullable
    private String mGroupDescription;
    @Nullable
    private Capabilities mGroupCapabilities;
    private long mJoinPermissions;
    private Group mGroup;
    private ImageId mAvatarId;
    private Bitmap mAvatar;
    private File mAvatarFile;
    private GroupConversation mGroupConversation;
    private List<GroupMember> mGroupMembers;
    private List<Contact> mInviteMembers;
    private Contact mCurrentInvitedContact;
    private GroupMember mGroupMemberInviteAsContact;
    @Nullable
    private String mFindName;
    private Space mSpace;
    @Nullable
    private UUID mLeaveMemberTwincodeId;

    public GroupService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "GroupService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        mConversationService = mTwinmeContext.getConversationService();
        mConversationService.addServiceObserver(mConversationServiceObserver);
        super.onTwinlifeReady();
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife() && mConversationService != null) {
            mConversationService.removeServiceObserver(mConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
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

    public void getContact(UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContact");
        }

        mContactId = contactId;
        mWork |= GET_CONTACT;
        mState &= ~(GET_CONTACT | GET_CONTACT_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void getGroup(@NonNull UUID groupId, boolean largeAvatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroup: groupId=" + groupId);
        }

        EventMonitor.info(LOG_TAG, "Get group ", groupId);

        mGroupId = groupId;
        if (largeAvatar) {
            mWork |= GET_GROUP | GET_GROUP_IMAGE | LIST_GROUP_MEMBER;
            mState &= ~(GET_GROUP | GET_GROUP_DONE | GET_GROUP_IMAGE | GET_GROUP_IMAGE_DONE | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE);
        } else {
            mWork |= GET_GROUP | LIST_GROUP_MEMBER;
            mState &= ~(GET_GROUP | GET_GROUP_DONE | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE);
        }

        showProgressIndicator();
        startOperation();
    }

    /**
     * Create a new group with the name and picture and invite the selected contacts.
     * <p>
     * Upon successful creation and invitation of the last contact, the onCreateGroup()
     * observer callback is invoked.
     *
     * @param name        the group name.
     * @param avatar      the group picture.
     * @param avatarFile  the path of the group picture.
     * @param members     the list of members to invite.
     * @param permissions the default join permission for members.
     */
    public void createGroup(String name, @Nullable String description, Bitmap avatar, File avatarFile, List<Contact> members, long permissions) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createGroup: name=" + name + " avatarFile=" + avatarFile);
        }

        EventMonitor.info(LOG_TAG, "Create group ", name, " with ", members.size(), " invitations");

        mWork |= CREATE_GROUP | LIST_GROUP_MEMBER;
        mState &= ~(CREATE_GROUP | CREATE_GROUP_DONE | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE);
        mGroupName = name;
        mGroupDescription = description;
        mAvatar = avatar;
        mAvatarFile = avatarFile;
        mJoinPermissions = permissions;
        inviteContacts(members);
    }

    /**
     * Invite a list of contacts in the current group.
     *
     * @param members the list of members to invite.
     */
    public void inviteContacts(List<Contact> members) {
        if (DEBUG) {
            Log.d(LOG_TAG, "inviteContacts: contacts=" + members);
        }

        mInviteMembers = members;
        mWork |= INVITE_GROUP_MEMBER;
        mState &= ~(INVITE_GROUP_MEMBER | INVITE_GROUP_MEMBER_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void updateGroup(@NonNull String groupName, @Nullable String groupDescription) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateGroup: groupName=" + groupName);
        }

        updateGroup(groupName, groupDescription, null, null, null, null);
    }

    public void updateGroup(@NonNull String groupName, @Nullable String groupDescription, @Nullable Bitmap groupAvatar, @Nullable File groupAvatarFile, @Nullable Long permissions, @Nullable Capabilities groupCapabilities) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateGroup: groupName=" + groupName + " groupAvatarFile=" + groupAvatarFile);
        }

        mGroupName = groupName;
        mAvatar = groupAvatar;
        mAvatarFile = groupAvatarFile;
        mGroupDescription = groupDescription;
        mGroupCapabilities = groupCapabilities;
        mWork |= UPDATE_GROUP;
        mState &= ~(UPDATE_GROUP | UPDATE_GROUP_DONE);
        showProgressIndicator();
        if(permissions != null) {
            mTwinmeContext.execute(() -> mConversationService.setPermissions(mGroup, null, permissions));
        }
        startOperation();
    }

    public void updateGroup(@NonNull String groupName, @Nullable String groupDescription, @Nullable Bitmap groupAvatar, @Nullable File groupAvatarFile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateGroup: groupName=" + groupName + " groupAvatarFile=" + groupAvatarFile);
        }

        mGroupName = groupName;
        mAvatar = groupAvatar;
        mAvatarFile = groupAvatarFile;
        mGroupDescription = groupDescription;
        mWork |= UPDATE_GROUP;
        mState &= ~(UPDATE_GROUP | UPDATE_GROUP_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void updateGroupPermissions(long permissions) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateGroupPermissions: " + permissions);
        }

        mTwinmeContext.execute(() -> mConversationService.setPermissions(mGroup, null, permissions));
    }

    /**
     * Create a new group with the name and picture and invite the selected contacts.
     * <p>
     * Upon successful creation and invitation of the last contact, the onCreateGroup()
     * observer callback is invoked.
     *
     * @param member the group name.
     */
    public void createInvitation(GroupMember member) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createInvitation: member=" + member);
        }

        showProgressIndicator();
        mGroupMemberInviteAsContact = member;
        mWork |= CREATE_INVITATION;
        mState &= ~(CREATE_INVITATION | CREATE_INVITATION_DONE);

        startOperation();
    }

    public void withdrawInvitation(ConversationService.InvitationDescriptor invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "withdrawInvitation: invitation=" + invitation);
        }

        mTwinmeContext.withdrawInviteGroup(0, invitation);
    }

    /**
     * The member is removed from the group.
     * <p>
     * Execute the leaveGroup() operation to send the leave-group message to other members.
     *
     * @param memberTwincodeId the group member to remove.
     */
    public void leaveGroup(@NonNull UUID memberTwincodeId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "leaveGroup: group=" + mGroup + " member=" + memberTwincodeId);
        }

        mWork |= MEMBER_LEAVE_GROUP;
        mLeaveMemberTwincodeId = memberTwincodeId;
        showProgressIndicator();
        startOperation();
    }

    public void getCurrentSpace() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentSpace");
        }

        showProgressIndicator();

        mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space)-> {
            mSpace = space;
            runOnUiThread(() -> {
                if (mObserver != null) {
                    if (space != null) {
                        mObserver.onGetCurrentSpace(space);
                    } else {
                        mObserver.onGetSpaceNotFound();
                    }
                }
            });
            onOperation();
        });
    }

    public void getSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: spaceId=" + spaceId);
        }

        showProgressIndicator();

        mTwinmeContext.getSpace(spaceId, (ErrorCode errorCode, Space space) -> {
            runOnGetSpace(mObserver, space, null);
            mState |= GET_SPACE_DONE;
            onOperation();
        });
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

    //
    // Private methods
    //

    private void onGetContacts(@NonNull List<Contact> contacts) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContacts");
        }

        EventMonitor.info(LOG_TAG, "Found ", contacts.size(), " contacts");

        mState |= GET_CONTACTS_DONE;
        runOnGetContacts(mObserver, contacts);
        onOperation();
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact");
        }

        mState |= GET_CONTACT_DONE;
        if (contact != null) {
            Bitmap avatar = getImage(contact);
            runOnGetContact(mObserver, contact, avatar);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_CONTACT, errorCode, null);
        }
        onOperation();
    }

    private void onGetGroup(@NonNull ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetProfileGroup: group=" + group);
        }

        mState |= GET_GROUP_DONE;
        mGroup = group;
        if (group != null) {
            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, group.getId(), mGroupId);

            // We want to use the twincode group name
            mGroupName = group.getGroupName();
            mAvatarId = mGroup.getAvatarId();
            mAvatar = getImage(group);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroupNotFound();
                }
            });
        } else {
            onError(GET_GROUP, errorCode, null);
        }
        onOperation();
    }

    private void onListGroupMember(@NonNull ErrorCode errorCode, @Nullable List<GroupMember> groupMembers) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onListGroupMember: errorCode=" + errorCode + " groupMembers=" + groupMembers);
        }

        if (errorCode != ErrorCode.SUCCESS || groupMembers == null) {

            onError(LIST_GROUP_MEMBER, errorCode, null);
            return;
        }
        mGroupMembers = groupMembers;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetGroup(mGroup, mGroupMembers, mGroupConversation);
            }
        });
        mState |= LIST_GROUP_MEMBER_DONE;
        onOperation();
    }

    private void onCreateGroup(@NonNull Group group, @NonNull GroupConversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateGroup: group=" + group);
        }

        EventMonitor.info(LOG_TAG, "Group ", group.getId(), " created with twincode ", group.getGroupTwincodeOutboundId());

        mState |= CREATE_GROUP_DONE;
        mGroup = group;
        mGroupId = group.getId();
        mGroupConversation = conversation;
        mConversationService.setPermissions(mGroup, null, mJoinPermissions);
        nextInviteMember();
        onOperation();
    }

    private void onUpdateInvitation(@NonNull Conversation conversation, @NonNull InvitationDescriptor invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateInvitation conversation=" + conversation + " invitation=" + invitation);
        }

        final UUID groupId = conversation.getContactId();
        if (!groupId.equals(mGroupId)) {

            return;
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onInviteGroup(conversation, invitation);
            }
        });
    }

    private void onInviteGroup(@NonNull Conversation conversation, @NonNull InvitationDescriptor invitation) {

        EventMonitor.info(LOG_TAG, "Invitation in group ", invitation.getGroupTwincodeId(), " for ", conversation.getContactId());

        mState |= INVITE_GROUP_MEMBER_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onInviteGroup(conversation, invitation);
            }
        });
        nextInviteMember();
        onOperation();
    }

    private void nextInviteMember() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextInviteMember");
        }

        if (mInviteMembers == null || mInviteMembers.isEmpty()) {
            mCurrentInvitedContact = null;
            mState |= INVITE_GROUP_MEMBER | INVITE_GROUP_MEMBER_DONE;
            runOnUiThread(() -> {
                if (mObserver != null) {
                    if ((mWork & CREATE_GROUP) != 0) {
                        mObserver.onCreateGroup(mGroup, mGroupConversation);
                    } else {
                        mObserver.onGetGroup(mGroup, mGroupMembers, mGroupConversation);
                    }
                }
            });
        } else {
            mCurrentInvitedContact = mInviteMembers.remove(0);
            mState &= ~(INVITE_GROUP_MEMBER | INVITE_GROUP_MEMBER_DONE);
        }
    }

    private void onUpdateGroup(@NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateGroup: group=" + group);
        }

        mState |= UPDATE_GROUP_DONE;

        mGroup = group;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateGroup(group, mAvatar);
            }
        });

        onOperation();
    }

    private void onDeleteGroup(@NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteGroup: contactId=" + groupId);
        }

        if (!groupId.equals(mGroupId)) {

            return;
        }
        mState |= DELETE_GROUP_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteGroup(groupId);
            }
        });

        onOperation();
    }

    private void onLeaveGroup(@NonNull GroupConversation conversation, @NonNull UUID memberTwincodeId) {

        if (mGroup == null || conversation.getSubject() != mGroup) {
            return;
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onLeaveGroup(mGroup, memberTwincodeId);
            }
        });
        onOperation();
    }

    private void onJoinGroup(@Nullable GroupConversation conversation, @SuppressWarnings("unused") @Nullable InvitationDescriptor invitation, @SuppressWarnings("unused") @Nullable UUID memberId) {

        if (conversation != null && conversation.getState() == GroupConversation.State.JOINED) {
            final UUID groupId = conversation.getContactId();

            // If this is our group, refresh the information about the group and its members.
            if (mGroupId != null && mGroupId.equals(groupId)) {
                mWork |= GET_GROUP | LIST_GROUP_MEMBER;
                mState &= ~(GET_GROUP | GET_GROUP_DONE | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE | GET_PENDING_INVITATIONS);
                onOperation();
            }
        }
    }

    private void onCreateInvitation(@NonNull Invitation invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateInvitation: invitation=" + invitation);
        }

        mState |= CREATE_INVITATION_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateInvitation(invitation);
            }
        });
        onOperation();
    }

    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
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

        // We must get the group object.
        if ((mWork & GET_GROUP) != 0) {
            if (mGroupId != null && (mState & GET_GROUP) == 0) {
                mState |= GET_GROUP;
                mTwinmeContext.getGroup(mGroupId, this::onGetGroup);
                return;
            }
            if ((mState & GET_GROUP_DONE) == 0) {
                return;
            }

            // Get the list of pending invitations if the observer implements the PendingInvitationsObserver method.
            if ((mState & GET_PENDING_INVITATIONS) == 0) {
                mState |= GET_PENDING_INVITATIONS;
                if (mObserver instanceof PendingInvitationsObserver) {
                    Map<UUID, InvitationDescriptor> pendingInvitations = mTwinmeContext.getConversationService().listPendingInvitations(mGroup);
                    runOnUiThread(() -> {
                        PendingInvitationsObserver observer = (PendingInvitationsObserver) mObserver;
                        if (observer != null) {
                            observer.onListPendingInvitations(pendingInvitations);
                        }
                    });
                }
            }
        }

        // Get the group normal image if we can.
        if ((mWork & GET_GROUP_IMAGE) != 0 && mAvatarId != null) {
            if ((mState & GET_GROUP_IMAGE) == 0) {
                mState |= GET_GROUP_IMAGE;

                mTwinmeContext.getImageService().getImageFromServer(mAvatarId, ImageService.Kind.NORMAL, (ErrorCode errorCode, Bitmap image) -> {
                    mState |= GET_GROUP_IMAGE_DONE;
                    mAvatar = image;
                    if (image != null) {
                        runOnUiThread(() -> {
                            if (mObserver != null) {
                                mObserver.onUpdateGroup(mGroup, image);
                            }
                        });
                    }
                    onOperation();
                });
                return;
            }
            if ((mState & GET_GROUP_IMAGE_DONE) == 0) {
                return;
            }
        }

        // We must get the contact.
        if ((mWork & GET_CONTACT) != 0 && mContactId != null) {
            if ((mState & GET_CONTACT) == 0) {
                mState |= GET_CONTACT;
                mTwinmeContext.getContact(mContactId, this::onGetContact);
                return;
            }
            if ((mState & GET_CONTACT_DONE) == 0) {
                return;
            }
        }

        // We must get the list of contacts.
        if ((mWork & GET_CONTACTS) != 0) {
            if ((mState & GET_CONTACTS) == 0) {
                mState |= GET_CONTACTS;
                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Contact)) {
                            return false;
                        }

                        final Contact contact = (Contact) object;
                        return !contact.isTwinroom() && contact.hasPeer();
                    }
                };
                mTwinmeContext.findContacts(filter, this::onGetContacts);
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
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Contact)) {
                            return false;
                        }

                        final Contact contact = (Contact) object;
                        String contactName = normalize(contact.getName());
                        return !contact.isTwinroom() && contact.hasPeer() && contactName.contains(mFindName);
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

        // We must get the group conversation.
        if ((mWork & LIST_GROUP_MEMBER) != 0 && mGroup != null) {
            if ((mState & LIST_GROUP_MEMBER) == 0) {
                mState |= LIST_GROUP_MEMBER;

                mGroupConversation = (GroupConversation) mConversationService.getConversation(mGroup);

                if (mGroupConversation == null) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onGetGroupNotFound();
                        }
                    });
                    return;

                }
                mTwinmeContext.listGroupMembers(mGroup, MemberFilter.JOINED_MEMBERS, this::onListGroupMember);
                return;
            }
            if ((mState & LIST_GROUP_MEMBER_DONE) == 0) {
                return;
            }
        }

        // We must create a group and we have not done it yet.
        if ((mWork & CREATE_GROUP) != 0) {
            if ((mState & CREATE_GROUP) == 0) {
                mState |= CREATE_GROUP;
                long requestId = newOperation(CREATE_GROUP);

                mTwinmeContext.createGroup(requestId, mGroupName, mGroupDescription, mAvatar, mAvatarFile);
                return;
            }
            if ((mState & CREATE_GROUP_DONE) == 0) {
                return;
            }
        }

        // We must invite the contacts stored in mInviteMembers after the group is created.
        if (((mWork & INVITE_GROUP_MEMBER) != 0) && (mGroup != null)) {
            if ((mState & INVITE_GROUP_MEMBER) == 0) {

                if (mCurrentInvitedContact == null) {
                    nextInviteMember();
                }

                // nextInviteMember can clear the INVITE_GROUP_MEMBER state, mark it after its possible call.
                mState |= INVITE_GROUP_MEMBER;
                if (mCurrentInvitedContact != null) {
                    Conversation conversation = mConversationService.getOrCreateConversation(mCurrentInvitedContact);
                    if (conversation == null) {
                        onError(INVITE_GROUP_MEMBER, ErrorCode.ITEM_NOT_FOUND, null);
                    } else {
                        UUID cid = conversation.getId();
                        long requestId = newOperation(INVITE_GROUP_MEMBER);
                        EventMonitor.event("Invite " + Utils.toLog(cid) + " in " + Utils.toLog(mGroup.getGroupTwincodeOutboundId()));
                        ErrorCode result = mConversationService.inviteGroup(requestId, conversation, mGroup, mGroupName);
                        if (result != ErrorCode.SUCCESS) {
                            onError(INVITE_GROUP_MEMBER, result, cid.toString());
                        }
                    }
                }
            }
            if ((mState & INVITE_GROUP_MEMBER_DONE) == 0) {
                return;
            }

        }

        // We must update the group.
        if ((mWork & MEMBER_LEAVE_GROUP) != 0 && mLeaveMemberTwincodeId != null) {
            if ((mState & MEMBER_LEAVE_GROUP) == 0) {
                mState |= MEMBER_LEAVE_GROUP;

                // Execute the leaveGroup operation first, don't wait for the network: the operation is queued.
                long requestId = newOperation(MEMBER_LEAVE_GROUP);
                ErrorCode result = mConversationService.leaveGroup(requestId, mGroup, mLeaveMemberTwincodeId);

                if (result != ErrorCode.SUCCESS) {
                    finishOperation(requestId);
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onLeaveGroup(mGroup, mLeaveMemberTwincodeId);
                        }
                        mLeaveMemberTwincodeId = null;
                    });
                }
                if (mLeaveMemberTwincodeId.equals(mGroup.getMemberTwincodeOutboundId())) {
                    // Mark the group as leaving and save it.
                    mGroup.markLeaving();
                    mWork |= UPDATE_GROUP;
                    mState &= ~(UPDATE_GROUP | UPDATE_GROUP_DONE);
                }
            }
        }

        // We must update the group.
        if ((mWork & UPDATE_GROUP) != 0) {
            if ((mState & UPDATE_GROUP) == 0) {
                mState |= UPDATE_GROUP;
                long requestId = newOperation(UPDATE_GROUP);
                mTwinmeContext.updateGroup(requestId, mGroup, mGroupName, mGroupDescription, mAvatar, mAvatarFile, mGroupCapabilities);
                return;
            }
            if ((mState & UPDATE_GROUP_DONE) == 0) {
                return;
            }
        }

        // We must delete the group.
        if ((mWork & DELETE_GROUP) != 0) {
            if ((mState & DELETE_GROUP) == 0) {
                mState |= DELETE_GROUP;
                long requestId = newOperation(DELETE_GROUP);
                mTwinmeContext.deleteGroup(requestId, mGroup);
                return;
            }
            if ((mState & DELETE_GROUP_DONE) == 0) {
                return;
            }
        }

        // We must create a group and we have not done it yet.
        if ((mWork & CREATE_INVITATION) != 0) {
            if ((mState & CREATE_INVITATION) == 0) {
                mState |= CREATE_INVITATION;
                long requestId = newOperation(CREATE_INVITATION);
                mTwinmeContext.createInvitation(requestId, mGroupMemberInviteAsContact);
                return;
            }
            if ((mState & CREATE_INVITATION_DONE) == 0) {
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
            switch (operationId) {
                case GET_GROUP:
                case UPDATE_GROUP:
                    hideProgressIndicator();
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onGetGroupNotFound();
                        }
                    });
                    return;

                case GET_SPACE:
                    runOnGetSpace(mObserver, null, null);
                    return;

                case INVITE_GROUP_MEMBER:

                    hideProgressIndicator();
                    runOnGetContactNotFound(mObserver);
                    return;

                default:
                    break;
            }
        }

        if (errorCode == ErrorCode.LIMIT_REACHED) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onErrorLimitReached();
                }
            });
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
