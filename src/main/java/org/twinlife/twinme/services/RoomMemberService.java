/*
 *  Copyright (c) 2020-2024 twinlife SA.
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.TransientObjectDescriptor;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Invitation;
import org.twinlife.twinme.models.RoomCommand;
import org.twinlife.twinme.models.RoomCommandResult;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RoomMemberService extends AbstractTwinmeService {
    private static final String LOG_TAG = "RoomMemberService";
    private static final boolean DEBUG = false;

    private static final int GET_ROOM = 1;
    private static final int GET_ROOM_DONE = 1 << 2;
    private static final int GET_ROOM_ADMIN = 1 << 3;
    private static final int GET_ROOM_ADMIN_DONE = 1 << 4;
    private static final int GET_ROOM_MEMBERS = 1 << 5;
    private static final int GET_ROOM_MEMBERS_DONE = 1 << 6;
    private static final int GET_ROOM_MEMBER = 1 << 7;
    private static final int GET_ROOM_MEMBER_DONE = 1 << 8;
    private static final int GET_ROOM_MEMBER_AVATAR = 1 << 9;
    private static final int GET_ROOM_MEMBER_AVATAR_DONE = 1 << 10;
    private static final int SET_ROOM_ADMINISTRATOR = 1 << 11;
    private static final int SET_ROOM_ADMINISTRATOR_DONE = 1 << 12;
    private static final int REMOVE_MEMBER = 1 << 13;
    private static final int REMOVE_MEMBER_DONE = 1 << 14;
    private static final int INVITE_MEMBER = 1 << 15;
    private static final int INVITE_MEMBER_DONE = 1 << 16;
    private static final int REMOVE_ROOM_ADMINISTRATOR = 1 << 17;
    private static final int REMOVE_ROOM_ADMINISTRATOR_DONE = 1 << 18;

    private static final int MAX_MEMBERS = 20;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onGetRoomAdmins(@NonNull List<TwincodeOutbound> admins);

        void onGetRoomMembers(@NonNull List<TwincodeOutbound> members);

        void onGetRoomAdminAvatar(@NonNull TwincodeOutbound twincodeOutbound, @Nullable Bitmap avatar);

        void onGetRoomMemberAvatar(@NonNull TwincodeOutbound twincodeOutbound, @Nullable Bitmap avatar);

        void onSetAdministrator(@NonNull UUID adminId);

        void onRemoveAdministrator(@NonNull UUID adminId);

        void onRemoveMember(@NonNull UUID memberId);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (!mRoomId.equals(contact.getId())) {

                return;
            }

            if (getOperation(requestId) != null) {
                RoomMemberService.this.onUpdateContact(contact);
            }

            // May be we have received the private peer twincode and we can proceed with other operations.
            onOperation();
        }

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            if (!mRoomId.equals(contactId)) {

                return;
            }

            RoomMemberService.this.onDeleteContact(contactId);
        }

        @Override
        public void onCreateInvitation(long requestId, @NonNull Invitation invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateInvitation: requestId=" + requestId + " invitation=" + invitation);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            RoomMemberService.this.onCreateInvitation(invitation);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onPopDescriptor(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPopDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!(descriptor instanceof TransientObjectDescriptor)) {
                return;
            }

            TransientObjectDescriptor command = (TransientObjectDescriptor) descriptor;
            Object object = command.getObject();
            if (!(object instanceof RoomCommandResult)) {
                return;
            }

            RoomCommandResult result = (RoomCommandResult) object;
            if (result.getRequestId() != mRoomRequestId) {
                return;
            }

            long operationId = getOperation(mRoomRequestId);

            if (operationId == GET_ROOM_ADMIN) {
                RoomMemberService.this.onGetRoomAdmin(result);
            } else if (operationId == GET_ROOM_MEMBERS) {
                RoomMemberService.this.onGetRoomMembers(result);
            } else if (operationId == SET_ROOM_ADMINISTRATOR) {
                RoomMemberService.this.onSetRoomAdmin(result);
            } else if (operationId == REMOVE_ROOM_ADMINISTRATOR) {
                RoomMemberService.this.onRemoveRoomAdmin(result);
            } else if (operationId == REMOVE_MEMBER) {
                RoomMemberService.this.onRemoveMember(result);
            }
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

            RoomMemberService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @NonNull
    private final ConversationServiceObserver mConversationServiceObserver;
    private ConversationService mConversationService;
    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    @NonNull
    private final UUID mRoomId;
    private long mRoomRequestId;
    private Contact mRoom;
    private List<UUID> mAdminIds;
    private List<UUID> mMemberIds;
    private UUID mCurrentRoomMemberId;
    private List<TwincodeOutbound> mRoomAdmins;
    private List<TwincodeOutbound> mRoomMembers;
    private TwincodeOutbound mCurrentTwincodeOutbound;
    private UUID mAdminMemberId;
    private UUID mRemoveMemberId;
    private UUID mInviteMemberId;
    private boolean mGetAdminsDone;

    public RoomMemberService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
                             @NonNull UUID roomId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "RoomMemberService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mGetAdminsDone = false;
        mObserver = observer;
        mRoomId = roomId;
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

            if ((mState & GET_ROOM_MEMBER) != 0 && (mState & GET_ROOM_MEMBER_DONE) == 0) {
                mState &= ~GET_ROOM_MEMBER;
            }
            if ((mState & GET_ROOM_MEMBER_AVATAR) != 0 && (mState & GET_ROOM_MEMBER_AVATAR_DONE) == 0) {
                mState &= ~GET_ROOM_MEMBER_AVATAR;
            }
        }
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

    public void nextMembers() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextMembers");
        }

        if (mMemberIds.isEmpty()) {
            return;
        }

        mWork |= GET_ROOM_MEMBER;
        mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);

        nextRoomMember();
        showProgressIndicator();
        startOperation();
    }

    public void setRoomAdministrator(@NonNull UUID memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setRoomAdministrator: memberId=" + memberId);
        }

        mAdminMemberId = memberId;
        mWork |= SET_ROOM_ADMINISTRATOR;
        mState &= ~(SET_ROOM_ADMINISTRATOR | SET_ROOM_ADMINISTRATOR_DONE);
        startOperation();
    }

    public void removeRoomAdministrator(@NonNull UUID memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "removeRoomAdministrator: memberId=" + memberId);
        }

        mAdminMemberId = memberId;
        mWork |= REMOVE_ROOM_ADMINISTRATOR;
        mState &= ~(REMOVE_ROOM_ADMINISTRATOR | REMOVE_ROOM_ADMINISTRATOR_DONE);
        startOperation();
    }

    public void inviteMember(@NonNull UUID memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "inviteMember: memberId=" + memberId);
        }

        mInviteMemberId = memberId;
        mWork |= INVITE_MEMBER;
        mState &= ~(INVITE_MEMBER | INVITE_MEMBER_DONE);
        startOperation();
    }

    public void removeMember(@NonNull UUID memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "removeMember: memberId=" + memberId);
        }

        mRemoveMemberId = memberId;
        mWork |= REMOVE_MEMBER;
        mState &= ~(REMOVE_MEMBER | REMOVE_MEMBER_DONE);
        startOperation();
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

        //
        // Step 1: Get the current room.
        //
        if ((mState & GET_ROOM) == 0) {
            mState |= GET_ROOM;

            mTwinmeContext.getContact(mRoomId, this::onGetRoom);
            return;
        }
        if ((mState & GET_ROOM_DONE) == 0) {
            return;
        }

        // If the room has no private peer yet, we cannot make API requests on it.
        // Every step below requires a valid private peer.
        if (mRoom != null && !mRoom.hasPrivatePeer()) {
            return;
        }

        //
        // Step 2: Get admin
        //
        if ((mState & GET_ROOM_ADMIN) == 0) {
            mState |= GET_ROOM_ADMIN;

            long requestId = newOperation(GET_ROOM_ADMIN);
            mRoomRequestId = requestId;
            if (DEBUG) {
                Log.d(LOG_TAG, "roomListMembers: requestId=" + requestId + " mRoom:" + mRoom);
            }
            mTwinmeContext.roomListMembers(requestId, mRoom, RoomCommand.LIST_ROLE_ADMINISTRATOR);
            return;
        }
        if ((mState & GET_ROOM_ADMIN_DONE) == 0) {
            return;
        }

        // Get room member avatar
        if (mCurrentTwincodeOutbound != null && (mWork & GET_ROOM_MEMBER_AVATAR) != 0) {
            if ((mState & GET_ROOM_MEMBER_AVATAR) == 0) {
                mState |= GET_ROOM_MEMBER_AVATAR;

                ImageId avatarId = mCurrentTwincodeOutbound.getAvatarId();
                if (avatarId != null) {
                    Bitmap image = mTwinmeContext.getImageService().getImage(avatarId, ImageService.Kind.THUMBNAIL);
                    if (image != null) {
                        onGetRoomMemberAvatar(mCurrentTwincodeOutbound, image);
                    } else {
                        // Keep current member information, proceed with a default avatar and with the next member.
                        final TwincodeOutbound twincodeOutbound = mCurrentTwincodeOutbound;
                        final boolean isAdmin = mRoomAdmins != null && !mRoomAdmins.isEmpty() || mMemberIds == null;
                        onGetRoomMemberAvatar(mCurrentTwincodeOutbound, mTwinmeApplication.getDefaultAvatar());

                        // Then, try to get the image from the server in background.
                        mTwinmeContext.getImageService().getImageFromServer(avatarId, ImageService.Kind.THUMBNAIL, (ErrorCode errorCode, Bitmap avatar) -> {
                            if (avatar != null && mObserver != null) {
                                runOnUiThread(() -> {
                                    if (mObserver != null) {
                                        if (isAdmin) {
                                            mObserver.onGetRoomAdminAvatar(twincodeOutbound, avatar);
                                        } else {
                                            mObserver.onGetRoomMemberAvatar(twincodeOutbound, avatar);
                                        }
                                    }
                                });
                            }
                        });
                    }
                } else {
                    onGetRoomMemberAvatar(mCurrentTwincodeOutbound, mTwinmeApplication.getDefaultAvatar());
                }
                return;
            }
            if ((mState & GET_ROOM_MEMBER_AVATAR_DONE) == 0) {
                return;
            }
        }

        //
        //  Get members list
        //
        if ((mWork & GET_ROOM_MEMBERS) != 0) {
            if ((mState & GET_ROOM_MEMBERS) == 0) {
                mState |= GET_ROOM_MEMBERS;

                long requestId = newOperation(GET_ROOM_MEMBERS);
                mRoomRequestId = requestId;
                if (DEBUG) {
                    Log.d(LOG_TAG, "roomListMembers: requestId=" + requestId + " mRoom:" + mRoom);
                }
                mTwinmeContext.roomListMembers(requestId, mRoom, RoomCommand.LIST_ROLE_MEMBER);
                return;
            }

            if ((mState & GET_ROOM_MEMBERS_DONE) == 0) {
                return;
            }
        }


        // Get room member name
        if (mCurrentRoomMemberId != null && (mWork & GET_ROOM_MEMBER) != 0) {
            if ((mState & GET_ROOM_MEMBER) == 0) {
                mState |= GET_ROOM_MEMBER;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwincodeOutboundService.getTwincode: twincodeOutboundId=" + mCurrentRoomMemberId);
                }
                mTwinmeContext.getTwincodeOutboundService().getTwincode(mCurrentRoomMemberId,
                        TwincodeOutboundService.REFRESH_PERIOD, this::onGetRoomMember);
                return;
            }
            if ((mState & GET_ROOM_MEMBER_DONE) == 0) {
                return;
            }
        }

        //
        // set administrator
        //
        if (mAdminMemberId != null && (mWork & SET_ROOM_ADMINISTRATOR) != 0) {
            if ((mState & SET_ROOM_ADMINISTRATOR) == 0) {
                mState |= SET_ROOM_ADMINISTRATOR;

                long requestId = newOperation(SET_ROOM_ADMINISTRATOR);
                mRoomRequestId = requestId;
                List<UUID> adminList = new ArrayList<>();
                adminList.add(mAdminMemberId);
                mTwinmeContext.roomSetRoles(requestId, mRoom, RoomCommand.ROLE_ADMINISTRATOR, adminList);
                return;
            }
            if ((mState & SET_ROOM_ADMINISTRATOR_DONE) == 0) {
                return;
            }
        }

        //
        // remove administrator
        //
        if (mAdminMemberId != null && (mWork & REMOVE_ROOM_ADMINISTRATOR) != 0) {
            if ((mState & REMOVE_ROOM_ADMINISTRATOR) == 0) {
                mState |= REMOVE_ROOM_ADMINISTRATOR;

                long requestId = newOperation(REMOVE_ROOM_ADMINISTRATOR);
                mRoomRequestId = requestId;
                List<UUID> adminList = new ArrayList<>();
                adminList.add(mAdminMemberId);
                mTwinmeContext.roomSetRoles(requestId, mRoom, RoomCommand.ROLE_MEMBER, adminList);
                return;
            }
            if ((mState & REMOVE_ROOM_ADMINISTRATOR_DONE) == 0) {
                return;
            }
        }

        //
        // remove member
        //
        if (mRemoveMemberId != null && (mWork & REMOVE_MEMBER) != 0) {
            if ((mState & REMOVE_MEMBER) == 0) {
                mState |= REMOVE_MEMBER;

                long requestId = newOperation(REMOVE_MEMBER);
                mRoomRequestId = requestId;
                mTwinmeContext.roomDeleteMember(requestId, mRoom, mRemoveMemberId);
                return;
            }
            if ((mState & REMOVE_MEMBER_DONE) == 0) {
                return;
            }
        }

        //
        // invite member
        //
        if (mInviteMemberId != null && (mWork & INVITE_MEMBER) != 0) {
            if ((mState & INVITE_MEMBER) == 0) {
                mState |= INVITE_MEMBER;

                long requestId = newOperation(INVITE_MEMBER);
                mTwinmeContext.createInvitation(requestId, mRoom, mInviteMemberId);
                return;
            }
            if ((mState & INVITE_MEMBER_DONE) == 0) {
                return;
            }
        }

        hideProgressIndicator();
    }

    private void onGetRoom(@NonNull ErrorCode errorCode, @Nullable Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoom: room=" + room);
        }

        mRoom = room;

        mState |= GET_ROOM_DONE;
        if (room != null) {
            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, room.getId(), mRoomId);

            Bitmap avatar = getImage(room);
            runOnGetContact(mObserver, room, avatar);
            if (avatar == null && room.getAvatarId() != null) {
                getImageFromServer(room);
            }
        }
        onOperation();
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, contact.getId(), mRoomId);

        Bitmap avatar = getImage(contact);
        runOnUpdateContact(mObserver, contact, avatar);
        if (avatar == null && contact.getAvatarId() != null) {
            getImageFromServer(contact);
        }
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        runOnDeleteContact(mObserver, contactId);
    }

    private void onCreateInvitation(@NonNull Invitation invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateInvitation: invitation=" + invitation);
        }

        mState |= INVITE_MEMBER_DONE;
        onOperation();
    }

    private void onGetRoomAdmin(@NonNull RoomCommandResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoomAdmin: result=" + result);
        }

        mState |= GET_ROOM_ADMIN_DONE;

        if (result.getMembers() != null) {
            mWork |= GET_ROOM_MEMBER;
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);

            mAdminIds = result.getMembers();
            mRoomAdmins = new ArrayList<>();
            nextRoomAdmin();
        } else {
            mGetAdminsDone = true;

            mWork |= GET_ROOM_MEMBERS;
            mState &= ~(GET_ROOM_MEMBERS | GET_ROOM_MEMBERS_DONE | GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);
        }

        onOperation();
    }

    private void onGetRoomMembers(@NonNull RoomCommandResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRoomCommandResult: result=" + result);
        }

        mState |= GET_ROOM_MEMBERS_DONE;

        if (result.getMembers() != null) {
            mWork |= GET_ROOM_MEMBER;
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);

            mMemberIds = result.getMembers();
            mRoomMembers = new ArrayList<>();
            nextRoomMember();
        }

        onOperation();
    }

    private void onGetRoomMember(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoomMember: member=" + twincodeOutbound);
        }

        mState |= GET_ROOM_MEMBER_DONE;

        if (!mGetAdminsDone) {
            if (twincodeOutbound != null) {
                mRoomAdmins.add(twincodeOutbound);
            }
            nextRoomAdmin();
        } else {
            if (twincodeOutbound != null) {
                mRoomMembers.add(twincodeOutbound);
            }
            nextRoomMember();
        }
        onOperation();
    }

    private void nextRoomMember() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextRoomMember");
        }

        if (mMemberIds.isEmpty() || mRoomMembers.size() >= MAX_MEMBERS) {

            mState |= GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE;

            mCurrentRoomMemberId = null;
            final List<TwincodeOutbound> members = new ArrayList<>(mRoomMembers);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetRoomMembers(members);
                }
            });
            mWork |= GET_ROOM_MEMBER_AVATAR;
            nextRoomMemberAvatar();
        } else {
            mCurrentRoomMemberId = mMemberIds.remove(0);
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);
        }
    }

    private void nextRoomAdmin() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextRoomAdmin");
        }

        if (mAdminIds.isEmpty()) {
            mGetAdminsDone = true;
            mState |= GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE;

            mCurrentRoomMemberId = null;
            final List<TwincodeOutbound> members = new ArrayList<>(mRoomAdmins);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetRoomAdmins(members);
                }
            });
            mWork |= GET_ROOM_MEMBERS;
            mWork |= GET_ROOM_MEMBER_AVATAR;
            nextRoomAdminAvatar();
        } else {
            mCurrentRoomMemberId = mAdminIds.remove(0);
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);
        }
    }

    private void onGetRoomMemberAvatar(TwincodeOutbound twincodeOutbound, Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoomMemberAvatar: twincodeOutbound=" + twincodeOutbound + " avatar:" + avatar);
        }

        mState |= GET_ROOM_MEMBER_AVATAR_DONE;

        final boolean isAdmin = mRoomAdmins != null && !mRoomAdmins.isEmpty() || mMemberIds == null;
        runOnUiThread(() -> {
            if (mObserver != null) {
                if (isAdmin) {
                    mObserver.onGetRoomAdminAvatar(twincodeOutbound, avatar);
                } else {
                    mObserver.onGetRoomMemberAvatar(twincodeOutbound, avatar);
                }
            }
        });

        if (mRoomAdmins != null && !mRoomAdmins.isEmpty()) {
            nextRoomAdminAvatar();
        } else if (mRoomMembers != null && !mRoomMembers.isEmpty()) {
            nextRoomMemberAvatar();
        } else if (mMemberIds == null) {
            mWork |= GET_ROOM_MEMBERS;
            mState &= ~(GET_ROOM_MEMBERS | GET_ROOM_MEMBERS_DONE);
        } else if (!mMemberIds.isEmpty()) {
            mState &= ~(GET_ROOM_MEMBER | GET_ROOM_MEMBER_DONE);
            nextRoomMember();
        }
        onOperation();
    }

    private void nextRoomMemberAvatar() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextRoomMemberAvatar");
        }

        if (mRoomMembers.isEmpty()) {
            mState |= GET_ROOM_MEMBER_AVATAR | GET_ROOM_MEMBER_AVATAR_DONE;
            mCurrentTwincodeOutbound = null;
        } else {
            mCurrentTwincodeOutbound = mRoomMembers.remove(0);
            mState &= ~(GET_ROOM_MEMBER_AVATAR | GET_ROOM_MEMBER_AVATAR_DONE);
        }
    }

    private void nextRoomAdminAvatar() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextRoomAdminAvatar");
        }

        if (mRoomAdmins.isEmpty()) {
            mState |= GET_ROOM_MEMBER_AVATAR | GET_ROOM_MEMBER_AVATAR_DONE;
            mCurrentTwincodeOutbound = null;
        } else {
            mCurrentTwincodeOutbound = mRoomAdmins.remove(0);
            mState &= ~(GET_ROOM_MEMBER_AVATAR | GET_ROOM_MEMBER_AVATAR_DONE);
        }
    }

    private void onSetRoomAdmin(@NonNull RoomCommandResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetRoomAdmin: result=" + result);
        }

        mState |= SET_ROOM_ADMINISTRATOR_DONE;

        if (result.getStatus() == RoomCommandResult.Status.SUCCESS) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onSetAdministrator(mAdminMemberId);
                }
            });
        }

        onOperation();
    }

    private void onRemoveRoomAdmin(@NonNull RoomCommandResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRemoveRoomAdmin: result=" + result);
        }

        mState |= REMOVE_ROOM_ADMINISTRATOR_DONE;

        if (result.getStatus() == RoomCommandResult.Status.SUCCESS ) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onRemoveAdministrator(mAdminMemberId);
                }
            });
        }

        onOperation();
    }

    private void onRemoveMember(@NonNull RoomCommandResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRemoveMember: result=" + result);
        }

        mState |= REMOVE_MEMBER_DONE;

        if (result.getStatus() == RoomCommandResult.Status.SUCCESS) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onRemoveMember(mRemoveMemberId);
                }
            });
        }

        onOperation();
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

        if (operationId == GET_ROOM) {

            hideProgressIndicator();

            if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
                runOnGetContactNotFound(mObserver);
                return;
            }
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}