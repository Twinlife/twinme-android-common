/*
 *  Copyright (c) 2018-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
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
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.GroupConversation;
import org.twinlife.twinlife.ConversationService.InvitationDescriptor;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.configuration.AppFlavor;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

/**
 * Group service to retrieve the information about an invitation and accept or decline it.
 */
public class GroupInvitationService extends AbstractTwinmeService {
    private static final String LOG_TAG = "GroupInvitationService";
    private static final boolean DEBUG = false;

    private static final int GET_SPACE = 1;
    private static final int GET_SPACE_DONE = 1 << 1;
    private static final int GET_CONTACT = 1 << 2;
    private static final int GET_CONTACT_DONE = 1 << 3;
    private static final int GET_INVITATION = 1 << 4;
    private static final int GET_TWINCODE = 1 << 5;
    private static final int GET_TWINCODE_DONE = 1 << 6;
    private static final int GET_TWINCODE_IMAGE = 1 << 7;
    private static final int GET_TWINCODE_IMAGE_DONE = 1 << 8;
    private static final int GET_GROUP = 1 << 9;
    private static final int ACCEPT_INVITATION = 1 << 12;
    private static final int ACCEPT_INVITATION_DONE = 1 << 13;
    private static final int DECLINE_INVITATION = 1 << 14;
    private static final int DECLINE_INVITATION_DONE = 1 << 15;
    private static final int DELETE_INVITATION = 1 << 16;
    private static final int DELETE_INVITATION_DONE = 1 << 17;
    private static final int SET_CURRENT_SPACE = 1 << 18;
    private static final int SET_CURRENT_SPACE_DONE = 1 << 19;
    private static final int MOVE_GROUP_SPACE = 1 << 20;
    private static final int MOVE_GROUP_SPACE_DONE = 1 << 21;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver, SpaceObserver {

        void onGetInvitation(@NonNull InvitationDescriptor invitation, @Nullable Bitmap avatar);

        void onAcceptedInvitation(GroupConversation conversation, InvitationDescriptor invitation);

        void onDeclinedInvitation(InvitationDescriptor invitation);

        void onMoveGroup(@NonNull Group group);

        void onGroupJoined(Group group, InvitationDescriptor invitation);

        void onDeletedInvitation();
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onMoveToSpace(long requestId, @NonNull Group group, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " group=" + group);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            GroupInvitationService.this.onMoveGroup(group);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            GroupInvitationService.this.onSetCurrentSpace(space);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onJoinGroup(long requestId, @Nullable GroupConversation conversation, @NonNull InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroup: requestId=" + requestId + " conversation=" + conversation);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            GroupInvitationService.this.onJoinGroup(operationId, conversation, invitation);
        }

        @Override
        public void onJoinGroupResponse(long requestId, @NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupResponse: requestId=" + requestId + " conversation=" + conversation);
            }

            if (invitation == null) {

                return;
            }

            GroupInvitationService.this.onJoinGroupResponse(conversation, invitation, invitation.getMemberTwincodeId());
        }

        @Override
        public void onMarkDescriptorDeleted(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onMarkDescriptorDeleted: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            GroupInvitationService.this.onMarkDescriptorDeleted(conversation, descriptor);
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

            GroupInvitationService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @Nullable
    private Observer mObserver;
    @NonNull
    private final ConversationServiceObserver mConversationServiceObserver;
    @NonNull
    private final DescriptorId mInvitationId;
    @NonNull
    private final UUID mContactId;
    private ConversationService mConversationService;
    private int mState = 0;
    private int mWork = 0;
    private Group mGroup;
    private InvitationDescriptor mInvitation;
    private ImageId mAvatarId;
    @Nullable
    private UUID mSpaceId;

    public GroupInvitationService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                  @NonNull Observer observer, @NonNull DescriptorId invitationId, @NonNull UUID contactId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "GroupInvitationService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " observer=" + observer + " invitationId=" + invitationId);
        }

        mObserver = observer;
        mInvitationId = invitationId;
        mContactId = contactId;
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

    @Override
    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;

            if ((mState & GET_TWINCODE) != 0 && (mState & GET_TWINCODE_DONE) == 0) {
                mState &= ~GET_TWINCODE;
            }
            if ((mState & GET_TWINCODE_IMAGE) != 0 && (mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                mState &= ~GET_TWINCODE_IMAGE;
            }
        }
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            if (mConversationService != null) {
                mConversationService.removeServiceObserver(mConversationServiceObserver);
            }
        }

        mObserver = null;
        super.dispose();
    }

    public void getSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: spaceId=" + spaceId);
        }

        mSpaceId = spaceId;
        mState &= ~(GET_SPACE | GET_SPACE_DONE);
        showProgressIndicator();
        startOperation();
    }

    /**
     * Accept the invitation to join the group.
     *
     */
    public void acceptInvitation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "acceptInvitation: invitation=" + mInvitation);
        }

        mWork |= ACCEPT_INVITATION;
        mState &= ~(ACCEPT_INVITATION | GET_GROUP | ACCEPT_INVITATION_DONE);
        showProgressIndicator();
        startOperation();
    }

    /**
     * Decline the invitation to join the group.
     *
     */
    public void declineInvitation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "declineInvitation: invitation=" + mInvitation);
        }

        mWork |= DECLINE_INVITATION;
        mState &= ~(DECLINE_INVITATION | DECLINE_INVITATION_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void setCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: space= " + space);
        }

        long requestId = newOperation(SET_CURRENT_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: requestId=" + requestId + " space= " + space);
        }

        showProgressIndicator();

        mTwinmeContext.setCurrentSpace(requestId, space);
        if (AppFlavor.TWINME_PLUS && !space.isSecret()) {
            mTwinmeContext.setDefaultSpace(space);
        }
    }

    public void moveGroupToSpace(@NonNull Space space, @NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "moveGroupToSpace: space= " + space + " group= " + group);
        }

        showProgressIndicator();

        long requestId = newOperation(MOVE_GROUP_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "moveToSpace: requestId=" + requestId + " space= " + space);
        }
        mTwinmeContext.moveToSpace(requestId, group, space);
    }

    //
    // Private methods
    //

    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        mState |= SET_CURRENT_SPACE_DONE;
        onOperation();
    }

    private void onGetSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        mState |= GET_SPACE_DONE;
        final Bitmap avatar = (space != null && space.getAvatarId() != null) ? getSpaceImage(space) : null;
        runOnGetSpace(mObserver, space, avatar);
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

    private void onMoveGroup(@NonNull Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveGroup: group=" + group);
        }

        mState |= MOVE_GROUP_SPACE_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onMoveGroup(group);
            }
        });
        onOperation();
    }

    private void onJoinGroup(@Nullable Integer operation, @Nullable GroupConversation conversation, @Nullable InvitationDescriptor invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onJoinGroup: operation=" + operation + " conversation=" + conversation + " invitation=" + invitation);
        }

        if (operation != null) {
            switch (operation) {
                case DECLINE_INVITATION:
                    mInvitation = invitation;
                    mState |= DECLINE_INVITATION_DONE;
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onDeclinedInvitation(mInvitation);
                        }
                    });
                    break;

                case ACCEPT_INVITATION:
                    mInvitation = invitation;
                    mState |= ACCEPT_INVITATION_DONE;
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onAcceptedInvitation(conversation, mInvitation);
                        }
                    });
                    break;

                default:
                    break;
            }
            onOperation();
        }
    }

    private void onJoinGroupResponse(@NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation, @Nullable UUID memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onJoinGroupResponse: conversation=" + conversation + " invitation=" + invitation + " memberId=" + memberId);
        }

        if (conversation.getState() == GroupConversation.State.JOINED) {

            // If this is our group, notify that we are now joined in this group.
            if (mGroup == conversation.getSubject()) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onGroupJoined(mGroup, invitation);
                    }
                });
            }
            onOperation();
        }
    }

    private void onGetTwincodeOutbound(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetTwincodeOutbound: errorCode=" + errorCode + " twincodeOutbound=" + twincodeOutbound);
        }

        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;
            return;
        }

        mState |= GET_TWINCODE_DONE;
        if (errorCode == ErrorCode.SUCCESS && twincodeOutbound != null) {
            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_TWINCODE, twincodeOutbound.getId(), mInvitation.getGroupTwincodeId());

            mAvatarId = twincodeOutbound.getAvatarId();
            runOnUiThread(() -> {
                if (mObserver != null && mAvatarId == null) {
                    mObserver.onGetInvitation(mInvitation, null);
                }
            });
        } else {
            onError(GET_TWINCODE, errorCode, mInvitation.getGroupTwincodeId().toString());
        }

        onOperation();
    }

    private void onMarkDescriptorDeleted(@NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMarkDescriptorDeleted: conversation=" + conversation + " descriptor=" + descriptor);
        }

        mState |= DELETE_INVITATION_DONE;
        mInvitation = null;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeletedInvitation();
            }
        });
        onOperation();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        // Get the specific or current space.
        if ((mState & GET_SPACE) == 0) {
            mState |= GET_SPACE;
            if (mSpaceId == null) {
                mTwinmeContext.getCurrentSpace(this::onGetSpace);
            } else {
                mTwinmeContext.getSpace(mSpaceId, this::onGetSpace);
            }
            return;
        }
        if ((mState & GET_SPACE_DONE) == 0) {
            return;
        }

        // We must get the contact.
        if ((mState & GET_CONTACT) == 0) {
            mState |= GET_CONTACT;
            mTwinmeContext.getContact(mContactId, this::onGetContact);
            return;
        }
        if ((mState & GET_CONTACT_DONE) == 0) {
            return;
        }

        // Get the group invitation.
        if ((mState & GET_INVITATION) == 0) {
            mState |= GET_INVITATION;
            mInvitation = mTwinmeContext.getConversationService().getInvitation(mInvitationId);
            if (mInvitation == null) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onDeletedInvitation();
                    }
                });
            }
        }

        // We must get the group twincode information.
        if (mInvitation != null) {
            if ((mState & GET_TWINCODE) == 0) {
                mState |= GET_TWINCODE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwincodeOutboundService.getTwincode: twincodeOutboundId=" + mInvitation.getGroupTwincodeId());
                }
                mTwinmeContext.getTwincodeOutboundService().getTwincode(mInvitation.getGroupTwincodeId(),
                        TwincodeOutboundService.REFRESH_PERIOD, this::onGetTwincodeOutbound);
                return;
            }
            if ((mState & GET_TWINCODE_DONE) == 0) {
                return;
            }
        }

        // We must get the group twincode image.
        if (mAvatarId != null) {
            if ((mState & GET_TWINCODE_IMAGE) == 0) {
                mState |= GET_TWINCODE_IMAGE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "ImageService.getImage: imageId=" + mAvatarId);
                }
                mTwinmeContext.getImageService().getImageFromServer(mAvatarId, ImageService.Kind.THUMBNAIL, (ErrorCode error, Bitmap image) -> {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            // Even if we have a null image, call the observer with the invitation because it was found.
                            mObserver.onGetInvitation(mInvitation, image);
                        }
                    });
                    mState |= GET_TWINCODE_IMAGE_DONE;
                    onOperation();
                });

                return;
            }
            if ((mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                return;
            }
        }

        // We must create a accept the group invitation and we have not done it yet.
        if ((mWork & ACCEPT_INVITATION) != 0 && mInvitation != null ) {
            // We must create a group and we have not done it yet.
            if ((mState & ACCEPT_INVITATION) == 0) {
                mState |= ACCEPT_INVITATION;
                long requestId = newOperation(ACCEPT_INVITATION);
                mTwinmeContext.createGroup(requestId, mInvitation);
                return;
            }
            if ((mState & ACCEPT_INVITATION_DONE) == 0) {
                return;
            }
        }

        // We must decline the group invitation and we have not done it yet.
        if ((mWork & DECLINE_INVITATION) != 0 && mInvitation != null) {
            if ((mState & DECLINE_INVITATION) == 0) {
                mState |= DECLINE_INVITATION;
                long requestId = newOperation(DECLINE_INVITATION);
                ErrorCode result = mConversationService.joinGroup(requestId, mInvitation.getDescriptorId(),null);
                if (result != ErrorCode.SUCCESS) {
                    onError(DECLINE_INVITATION, result, null);
                }
            }
            if ((mState & DECLINE_INVITATION_DONE) == 0) {
                return;
            }
        }

        // Invitation is no longer valid and must be removed.
        if ((mWork & DELETE_INVITATION) != 0 && mInvitation != null) {
            if ((mState & DELETE_INVITATION) == 0) {
                mState |= DELETE_INVITATION;
                long requestId = newOperation(DELETE_INVITATION);
                mConversationService.markDescriptorDeleted(requestId, mInvitation.getDescriptorId());
                return;
            }
            if ((mState & DELETE_INVITATION_DONE) == 0) {
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
            // The invitation descriptor can be valid but the group has been removed.
            // Trigger the delete invitation locally through the markDescriptorDeleted()
            // and notify the activity through the onDeleteInvitation() callback when markDescriptorDeleted has finished.

            switch (operationId) {

                case ACCEPT_INVITATION:
                    mState |= ACCEPT_INVITATION_DONE;
                    mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);
                    mWork |= DELETE_INVITATION;
                    return;

                case DECLINE_INVITATION:
                    mState |= DECLINE_INVITATION_DONE;
                    mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);
                    mWork |= DELETE_INVITATION;
                    return;

                case GET_TWINCODE:
                    mState |= GET_TWINCODE_DONE;
                    mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);
                    mWork |= DELETE_INVITATION;
                    return;

                case DELETE_INVITATION:
                    mState |= DELETE_INVITATION_DONE;
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onDeletedInvitation();
                        }
                    });
                    return;

                default:
                    break;
            }
        } else if (errorCode == ErrorCode.NO_PERMISSION) {

            // joinGroup() can return NO_PERMISSION which means the invitation has already been accepted or declined.
            switch (operationId) {
                case ACCEPT_INVITATION:
                    mState |= ACCEPT_INVITATION_DONE;
                    mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);
                    mWork |= DELETE_INVITATION;
                    return;

                case DECLINE_INVITATION:
                    mState |= DECLINE_INVITATION_DONE;
                    mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);
                    mWork |= DELETE_INVITATION;
                    return;

                default:
                    break;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
