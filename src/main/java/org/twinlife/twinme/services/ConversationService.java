/*
 *  Copyright (c) 2017-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService.ClearMode;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorAnnotation;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.GroupConversation;
import org.twinlife.twinlife.ConversationService.InvitationDescriptor;
import org.twinlife.twinlife.ConversationService.MemberFilter;
import org.twinlife.twinlife.ConversationService.UpdateType;
import org.twinlife.twinlife.ConversationService.AnnotationType;
import org.twinlife.twinlife.DisplayCallsMode;
import org.twinlife.twinlife.ExportedImageId;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.Typing;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ConversationService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ConversationService";
    private static final boolean DEBUG = false;

    private static final int MAX_OBJECTS = 100;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;
    private static final int GET_GROUP = 1 << 2;
    private static final int GET_GROUP_DONE = 1 << 3;
    private static final int GET_OR_CREATE_CONVERSATION = 1 << 4;
    private static final int LIST_GROUP_MEMBER = 1 << 5;
    private static final int LIST_GROUP_MEMBER_DONE = 1 << 6;
    private static final int GET_DESCRIPTORS = 1 << 7;
    private static final int GET_DESCRIPTORS_DONE = 1 << 8;
    private static final int LIST_OTHER_MEMBER = 1 << 9;
    private static final int LIST_OTHER_MEMBER_DONE = 1 << 10;
    private static final int PUSH_OBJECT = 1 << 12;
    private static final int MARK_DESCRIPTOR_READ = 1 << 14;
    private static final int MARK_DESCRIPTOR_DELETED = 1 << 16;
    private static final int DELETE_DESCRIPTOR = 1 << 18;
    private static final int PUSH_TYPING = 1 << 22;
    private static final int PUSH_GEOLOCATION = 1 << 23;
    private static final int SAVE_GEOLOCATION_MAP = 1 << 24;
    private static final int PUSH_FILE = 1 << 25;
    private static final int UPDATE_DESCRIPTOR = 1 << 26;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onGetGroup(@NonNull Group group, @NonNull List<GroupMember> groupMembers,
                        @NonNull GroupConversation conversation, @Nullable Bitmap avatar);

        void onGetGroupMembers(@NonNull List<GroupMember> groupMembers);

        void onDeleteGroup(UUID groupId);

        void onLeaveGroup(@NonNull GroupConversation group, @NonNull UUID memberTwincodeId);

        void onJoinGroup(@NonNull GroupConversation conversation, @Nullable InvitationDescriptor descriptor);

        void onGetConversation(@NonNull Conversation conversation);

        void onGetConversationImage(@NonNull ExportedImageId imageId, @NonNull Bitmap bitmap);

        void onResetConversation(@NonNull Conversation conversation, @NonNull ClearMode clearMode);

        void onGetDescriptors(@NonNull List<Descriptor> descriptors);

        void onPushDescriptor(@NonNull Descriptor descriptor);

        void onPopDescriptor(@NonNull Descriptor descriptor);

        void onUpdateDescriptor(@NonNull Descriptor descriptor, UpdateType updateType);

        void onMarkDescriptorRead(@NonNull Descriptor descriptor);

        void onMarkDescriptorDeleted(@NonNull Descriptor descriptor);

        void onDeleteDescriptors(@NonNull Set<DescriptorId> descriptorList);

        void onSendFilesFinished();

        void onErrorNoPermission();

        void onErrorFeatureNotSupportedByPeer();
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onDeleteGroup(long requestId, @NonNull UUID groupId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteGroup: requestId=" + requestId + " groupId=" + groupId);
            }

            ConversationService.this.onDeleteGroup(groupId);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            ConversationService.this.onUpdateContact(contact);
        }
    }

    private class ConversationServiceObserver extends org.twinlife.twinlife.ConversationService.DefaultServiceObserver {

        @Override
        public void onResetConversation(@NonNull Conversation conversation, @NonNull ClearMode clearMode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onResetConversation: conversation=" + conversation);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onResetConversation(conversation, clearMode);
        }

        @Override
        public void onPushDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPushDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!isConversation(conversation)) {

                return;
            }

            final Integer operation = getOperation(requestId);
            ConversationService.this.onPushDescriptor(operation, descriptor);
            onOperation();
        }

        @Override
        public void onPopDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPopDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onPopDescriptor(descriptor);
        }

        @Override
        public void onUpdateDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor, UpdateType updateType) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onUpdateDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor +
                        " updateType=" + updateType);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onUpdateDescriptor(descriptor, updateType);
        }

        @Override
        public void onUpdateAnnotation(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor, @NonNull TwincodeOutbound annotatingUser) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onUpdateAnnotation: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor +
                        " annotatingUser=" + annotatingUser);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onUpdateDescriptor(descriptor, UpdateType.PEER_ANNOTATIONS);
        }

        @Override
        public void onMarkDescriptorRead(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onMarkDescriptorRead: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onMarkDescriptorRead(descriptor);
        }

        @Override
        public void onMarkDescriptorDeleted(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onMarkDescriptorDeleted: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onMarkDescriptorDeleted(descriptor);
        }

        @Override
        public void onDeleteDescriptors(long requestId, @NonNull Conversation conversation, @NonNull DescriptorId[] descriptorList) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteDescriptors: requestId=" + requestId + " conversation=" + conversation);
            }

            if (!isConversation(conversation)) {

                return;
            }

            ConversationService.this.onDeleteDescriptors(descriptorList);
        }

        @Override
        public void onInviteGroupRequest(long requestId, @NonNull Conversation conversation, @NonNull InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onInviteGroupRequest: requestId=" + requestId + " conversation=" + conversation + " invitation=" + invitation);
            }

            // Ignore a peer invitation that is not for the current conversation.
            if (!isConversation(conversation)) {

                return;
            }

            // Receiving a new invitation.
            ConversationService.this.onPopDescriptor(invitation);
        }

        @Override
        public void onJoinGroup(long requestId, @Nullable GroupConversation conversation, @NonNull InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroup: requestId=" + requestId + " conversation=" + conversation + " invitation=" + invitation);
            }

            // Ignore a peer invitation that is not for the current conversation.
            if (ConversationService.this.mPeerTwincodeOutboundId == null || !ConversationService.this.mPeerTwincodeOutboundId.equals(invitation.getTwincodeOutboundId())) {
                return;
            }

            // Invitation we are accepting: update its status.
            ConversationService.this.onUpdateDescriptor(invitation, UpdateType.TIMESTAMPS);
        }

        @Override
        public void onJoinGroupResponse(long requestId, @Nullable GroupConversation conversation, @Nullable InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupResponse: requestId=" + requestId + " conversation=" + conversation + " invitation=" + invitation);
            }

            // Ignore a peer invitation that is not for the current conversation.
            if (invitation == null || ConversationService.this.mPeerTwincodeOutboundId == null || !ConversationService.this.mPeerTwincodeOutboundId.equals(invitation.getTwincodeOutboundId())) {
                return;
            }

            // Invitation we have accepted and we got the response back: update its status.
            ConversationService.this.onUpdateDescriptor(invitation, UpdateType.TIMESTAMPS);
        }

        @Override
        public void onJoinGroupRequest(long requestId, @NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation, @NonNull UUID memberId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupRequest: requestId=" + requestId + " conversation=" + conversation + " invitation=" + invitation);
            }

            // Ignore an invitation that is not from the current conversation.
            if (ConversationService.this.mTwincodeOutboundId == null || invitation == null || !ConversationService.this.mTwincodeOutboundId.equals(invitation.getTwincodeOutboundId())) {
                ConversationService.this.onJoinGroup(conversation, invitation);
                return;
            }

            // Invitation we have sent and is accepted or refused: update its status.
            ConversationService.this.onUpdateDescriptor(invitation, UpdateType.TIMESTAMPS);
        }

        @Override
        public void onLeaveGroup(long requestId, @NonNull GroupConversation conversation, @NonNull UUID memberTwincodeId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onLeaveGroup: requestId=" + requestId
                        + " conversation=" + conversation + " memberTwincodeId=" + memberTwincodeId);
            }

            // The group conversation state can change to LEAVING and we want to hide it.
            ConversationService.this.onLeaveGroup(conversation, memberTwincodeId);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            if (requestId == BaseService.DEFAULT_REQUEST_ID) {
                if (errorCode == ErrorCode.FEATURE_NOT_SUPPORTED_BY_PEER) {
                    UUID conversationId;
                    try {
                        conversationId = UUID.fromString(errorParameter);
                    } catch (Exception exception) {
                        conversationId = null;
                    }
                    if (conversationId != null && conversationId.equals(ConversationService.this.mConversationId)) {

                        ConversationService.this.onConversationError(errorCode, errorParameter);
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

                ConversationService.this.onError(operationId, errorCode, errorParameter);
                onOperation();
            }
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
    private Filter<Descriptor> mDescriptorFilter;
    @Nullable
    private UUID mTwincodeOutboundId;
    private UUID mPeerTwincodeOutboundId;
    private boolean mIsGroup = false;
    @Nullable
    private UUID mConversationId;
    @Nullable
    private Conversation mConversation;
    private long mBeforeTimestamp = Long.MAX_VALUE;
    private boolean mGetDescriptorsDone = false;
    private boolean mFeatureNotSupportedByPeerMessage = true;
    private GroupConversation mGroupConversation;
    private final Map<UUID, GroupMember> mMembers = new HashMap<>();
    private List<UUID> mMemberTwincodes;
    private List<Descriptor> mDescriptors = null;
    private int mState = 0;
    private final ConversationServiceObserver mConversationServiceObserver;
    @Nullable
    private List<FileInfo> mFiles;
    @Nullable
    private FileInfo mCurrentFile;
    @NonNull
    private DisplayCallsMode mCallsMode;

    public ConversationService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ConversationService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mCallsMode = DisplayCallsMode.ALL;

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

    public void getConversationImage(@NonNull UUID imageId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getConversationImage: imageId=" + imageId);
        }

        ImageService imageService = mTwinmeContext.getImageService();
        ExportedImageId exportedImageId = imageService.getImageId(imageId);
        if (exportedImageId == null) {
            return;
        }
        mTwinmeContext.executeImage(() -> {
            Bitmap normalImage = imageService.getImage(exportedImageId, ImageService.Kind.NORMAL);
            if (normalImage != null) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onGetConversationImage(exportedImageId, normalImage);
                    }
                });
            }
        });
    }

    public void setActiveConversation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "setActiveConversation");
        }

        if (mConversation != null) {
            mTwinmeContext.setActiveConversation(mConversation);
        }
    }

    public void resetActiveConversation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "resetActiveConversation");
        }

        if (mConversation != null) {
            mTwinmeContext.resetActiveConversation(mConversation);
        }
    }

    public void clearMediaAndFile() {
        if (DEBUG) {
            Log.d(LOG_TAG, "clearMediaAndFile");
        }

        mBeforeTimestamp = Long.MAX_VALUE;
        mGetDescriptorsDone = false;
    }

    public void getContact(@NonNull UUID contactId, @NonNull DisplayCallsMode callsMode,
                           @Nullable Filter<Descriptor> descriptorFilter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContact: contactId=" + contactId + " descriptorFilter=" + descriptorFilter);
        }

        mContactId = contactId;
        mDescriptorFilter = descriptorFilter;
        mCallsMode = callsMode;
        mState &= ~(GET_CONTACT | GET_CONTACT_DONE | GET_OR_CREATE_CONVERSATION);
        mState |= GET_GROUP_DONE | GET_GROUP | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE;

        startOperation();
    }

    public void getGroup(@NonNull UUID groupId,  @NonNull DisplayCallsMode callsMode,
                         @Nullable Filter<Descriptor> descriptorFilter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroup: groupId=" + groupId + " descriptorFilter=" + descriptorFilter);
        }

        mContactId = groupId;
        mDescriptorFilter = descriptorFilter;
        mCallsMode = callsMode;
        mState &= ~(GET_GROUP | GET_GROUP_DONE | GET_OR_CREATE_CONVERSATION | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE);
        mState |= GET_CONTACT_DONE | GET_CONTACT;

        startOperation();
    }

    public void getPreviousObjectDescriptors() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPreviousObjectDescriptors");
        }

        if (mGetDescriptorsDone) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDescriptors(Collections.emptyList());
                }
            });
            return;
        }

        if ((mState & GET_DESCRIPTORS) != 0 && (mState & GET_DESCRIPTORS_DONE) != 0) {
            mState &= ~GET_DESCRIPTORS;
            mState &= ~GET_DESCRIPTORS_DONE;

            startOperation();
        }
    }

    public void listAnnotations(@NonNull DescriptorId descriptorId,
                                @NonNull TwinmeContext.ConsumerWithError<Map<TwincodeOutbound, DescriptorAnnotation>> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "listAnnotations: descriptorId=" + descriptorId);
        }

        mTwinmeContext.listAnnotations(descriptorId, consumer);
    }

    public void markDescriptorRead(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "markDescriptorRead: descriptorId=" + descriptorId);
        }

        long requestId = newOperation(MARK_DESCRIPTOR_READ);
        mTwinmeContext.markDescriptorRead(requestId, descriptorId);
    }

    public void markDescriptorDeleted(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "markDescriptorDeleted: descriptorId=" + descriptorId);
        }

        long requestId = newOperation(MARK_DESCRIPTOR_DELETED);
        mTwinmeContext.markDescriptorDeleted(requestId, descriptorId);
    }

    public void deleteDescriptor(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteDescriptor: descriptorId=" + descriptorId);
        }

        long requestId = newOperation(DELETE_DESCRIPTOR);
        mTwinmeContext.deleteDescriptor(requestId, descriptorId);
    }

    public void updateDescriptor(@NonNull DescriptorId descriptorId, String content) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDescriptor: descriptorId=" + descriptorId + " content=" + content);
        }

        mTwinmeContext.execute(() -> {
            long requestId = newOperation(UPDATE_DESCRIPTOR);
            mTwinmeContext.getConversationService().updateDescriptor(requestId, descriptorId, content, null, null);
        });
    }
    
    public void pushMessage(String message, boolean copyAllowed, long expireTimeout, org.twinlife.twinlife.ConversationService.DescriptorId replyTo) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushMessage: message=" + message + " copyAllowed=" + copyAllowed + " expireTimeout=" + expireTimeout + " replyTo=" + replyTo);
        }

        if (mConversation != null) {
            long requestId = newOperation(PUSH_OBJECT);
            mTwinmeContext.pushMessage(requestId, mConversation, null, replyTo, message, copyAllowed, expireTimeout * 1000);
        }
    }

    public void pushGeolocation(double longitude, double latitude, double altitude, double mapLongitudeDelta, double mapLatitudeDelta, long expireTimeout, DescriptorId replyTo) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushGeolocation: longitude=" + longitude + " latitude=" + latitude + " altitude=" + altitude + " mapLongitudeDelta=" + mapLongitudeDelta + " mapLatitudeDelta=" + mapLatitudeDelta + " expireTimeout=" + expireTimeout);
        }

        if (mConversation != null) {
            long requestId = newOperation(PUSH_GEOLOCATION);
            mTwinmeContext.pushGeolocation(requestId, mConversation, null, replyTo, longitude, latitude, altitude, mapLongitudeDelta, mapLatitudeDelta, null, expireTimeout * 1000);
        }
    }

    public void saveGeolocationMap(@NonNull Uri path, @NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "saveGeolocationMap: path=" + path + " descriptorId=" + descriptorId);
        }

        if (mConversation != null) {
            long requestId = newOperation(SAVE_GEOLOCATION_MAP);
            mTwinmeContext.saveGeolocationMap(requestId, mConversation, descriptorId, path);
        }
    }

    public void pushTyping(Typing typing) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushTyping: typing=" + typing);
        }

        if (mConversation != null) {
            long requestId = newOperation(PUSH_TYPING);
            mTwinmeContext.pushTransientObject(requestId, mConversation, typing);
        }
    }

    public void pushFile(@NonNull Uri file, @NonNull String filename, @NonNull Descriptor.Type type, boolean toDelete, boolean allowCopy,
                         @Nullable UUID sendTo, @Nullable DescriptorId replyTo, long expireTimeout) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushFile: file=" + file);
        }

        synchronized (this) {
            if (mFiles == null) {
                mFiles = new ArrayList<>();
            }
            mFiles.add(new FileInfo(file, filename, type, toDelete, allowCopy, sendTo, replyTo, expireTimeout));
        }
        startOperation();
    }

    public synchronized boolean isSendingFiles() {

        return mFiles != null && (!mFiles.isEmpty() || mCurrentFile != null);
    }

    public void toggleAnnotation(@NonNull DescriptorId descriptorId, AnnotationType annotationType, int value) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setAnnotation: setAnnotation=" + descriptorId + " annotationType=" + annotationType + " value=" + value);
        }

        mTwinmeContext.toggleAnnotation(descriptorId, annotationType, value);
    }

    public void deleteAnnotation(@NonNull DescriptorId descriptorId, AnnotationType annotationType) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteAnnotation: setAnnotation=" + descriptorId + " annotationType=" + annotationType);
        }

        mTwinmeContext.deleteAnnotation(descriptorId, annotationType);
    }

    public void resetConversation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "resetConversation");
        }

        if (mConversation != null) {
            mTwinmeContext.execute(() -> {
                // Clear both sides of the conversation (ignore errors).
                mTwinmeContext.getConversationService().clearConversation(mConversation, System.currentTimeMillis(), ClearMode.CLEAR_BOTH);
            });
        }
    }

    @Override
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

    //
    // Private methods
    //

    private boolean isConversation(@Nullable Conversation conversation) {
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

            final Conversation conversation;
            if (mGroup != null && mTwincodeOutboundId != null) {
                // For a group, we must not create the conversation and the get can fail.
                conversation = mTwinmeContext.getConversationService().getConversation(mGroup);
                if (conversation != null) {
                    if (conversation instanceof GroupConversation) {
                        mGroupConversation = (GroupConversation) conversation;

                        mConversation = conversation;
                        mConversationId = conversation.getId();
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
                conversation = mTwinmeContext.getConversationService().getOrCreateConversation(mContact);
                if (conversation == null) {
                    onError(GET_OR_CREATE_CONVERSATION, ErrorCode.ITEM_NOT_FOUND, null);
                    return;
                }

                mConversation = conversation;
                mConversationId = conversation.getId();
            } else {
                conversation = null;
            }
            if (conversation != null) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onGetConversation(conversation);
                    }
                });
            }
        }

        //
        // Step 3: get the group members.
        //
        if (mGroup != null) {
            if ((mState & LIST_GROUP_MEMBER) == 0) {
                mState |= LIST_GROUP_MEMBER;

                mTwinmeContext.listGroupMembers(mGroup, MemberFilter.JOINED_MEMBERS, this::onListGroupMember);
                return;
            }

            if ((mState & LIST_GROUP_MEMBER_DONE) == 0) {
                return;
            }
        }

        if (mContact != null && mMemberTwincodes != null
                && !mMemberTwincodes.isEmpty() && (mState & LIST_OTHER_MEMBER_DONE) == 0) {
            return;
        }

        //
        // Step 3
        //

        boolean completedStep3 = true;

        if (mConversation != null) {
            if ((mState & GET_DESCRIPTORS) == 0) {
                mState |= GET_DESCRIPTORS;
                completedStep3 = false;

                long requestId = newOperation(GET_DESCRIPTORS);
                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationService.getDescriptors: requestId=" + requestId + " mConversation=" + mConversation +
                            " mBeforeTimestamp=" + mBeforeTimestamp + " max=" + MAX_OBJECTS);
                }

                List<Descriptor> descriptors = new ArrayList<>();

                boolean needMore = true;
                while (needMore) {
                    List<Descriptor> page = mTwinmeContext.getConversationService().getConversationDescriptors(mConversation, mCallsMode, mBeforeTimestamp, MAX_OBJECTS);

                    if (page != null) {
                        for (Descriptor d : page) {
                            if (d.getCreatedTimestamp() < mBeforeTimestamp) {
                                mBeforeTimestamp = d.getCreatedTimestamp();
                            }
                            if (mDescriptorFilter == null || mDescriptorFilter.accept(d)) {
                                descriptors.add(d);
                                if (descriptors.size() == MAX_OBJECTS) {
                                    break;
                                }
                            }
                        }

                        needMore = descriptors.size() < MAX_OBJECTS && page.size() == MAX_OBJECTS;
                    } else {
                        needMore = false;
                    }
                }

                onGetDescriptors(descriptors);
            }
            if ((mState & GET_DESCRIPTORS_DONE) == 0) {
                return;
            }
        }

        if (!completedStep3) {

            return;
        }

        // Handle sending files.
        if (mFiles != null && mConversation != null) {
            if (mCurrentFile == null) {
                nextPushFile();
            }
            if (mCurrentFile != null) {
                if ((mState & PUSH_FILE) == 0) {
                    mState |= PUSH_FILE;

                    long requestId = newOperation(PUSH_FILE);
                    mTwinmeContext.getConversationService().pushFile(requestId, mConversation, mCurrentFile.sendTo, mCurrentFile.replyTo,
                            mCurrentFile.file, mCurrentFile.filename, mCurrentFile.type, mCurrentFile.toDelete, mCurrentFile.allowCopy,
                            mCurrentFile.expireTimeout * 1000);
                }
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

        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);
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

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact contact=" + contact);
        }

        if (mContact == null || !mContact.getId().equals(contact.getId())) {

            return;
        }

        mContact = contact;
        mPeerTwincodeOutboundId = contact.getPeerTwincodeOutboundId();
        if (mPeerTwincodeOutboundId == null) {

            return;
        }

        Bitmap avatar = getImage(contact);
        if (avatar == null && contact.getAvatarId() != null) {
            getImageFromServer(contact);
        }
        runOnUpdateContact(mObserver, contact, avatar);
    }

    private void onGetGroup(@NonNull ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroup group=" + group);
        }

        mState |= GET_GROUP_DONE;
        mState |= GET_CONTACT;
        mState |= GET_CONTACT_DONE;
        mState &= ~(GET_OR_CREATE_CONVERSATION | LIST_GROUP_MEMBER | LIST_GROUP_MEMBER_DONE);

        mContact = group;
        mGroup = group;
        mIsGroup = true;
        if (group != null) {
            mTwincodeOutboundId = group.getTwincodeOutboundId();
            mPeerTwincodeOutboundId = group.getGroupTwincodeOutboundId();
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_GROUP, errorCode, null);
        }
        onOperation();
    }

    private void onDeleteGroup(@NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteGroup: groupId=" + groupId);
        }

        if (mGroup == null || !groupId.equals(mGroup.getId())) {

            return;
        }
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteGroup(groupId);
            }
        });
    }

    private void onLeaveGroup(@NonNull GroupConversation group, @NonNull UUID memberTwincodeId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onLeaveGroup: group=" + group + " memberTwincodeId=" + memberTwincodeId);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onLeaveGroup(group, memberTwincodeId);
            }
        });
    }

    private void onJoinGroup(@NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onJoinGroup: conversation=" + conversation);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onJoinGroup(conversation, invitation);
            }
        });
    }

    private void onListGroupMember(@NonNull ErrorCode errorCode, @Nullable List<GroupMember> list) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onListGroupMember: errorCode=" + errorCode + " list=" + list);
        }

        if (errorCode != ErrorCode.SUCCESS || list == null) {

            onError(LIST_GROUP_MEMBER, errorCode, null);
            return;
        }

        if (mMemberTwincodes == null || mMemberTwincodes.isEmpty()) {
            mState |= LIST_GROUP_MEMBER_DONE;
        } else {
            mState |= LIST_OTHER_MEMBER_DONE;
        }
        for (GroupMember groupMember : list) {
            mMembers.put(groupMember.getPeerTwincodeOutboundId(), groupMember);
        }
        if (mMemberTwincodes != null) {
            mMemberTwincodes.clear();
        }

        if (mGroup != null && mGroupConversation != null) {
            Bitmap avatar = getImage(mGroup);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroup(mGroup, list, mGroupConversation, avatar);
                }
            });

        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetGroupMembers(list);
                }
            });
        }
        final List<Descriptor> descriptors = mDescriptors;
        if (descriptors != null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDescriptors(descriptors);
                }
            });
            mDescriptors = null;
        }
        onOperation();
    }

    private void onResetConversation(@NonNull Conversation conversation, @NonNull ClearMode clearMode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onResetConversation: conversation=" + conversation + " clearMode=" + clearMode);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onResetConversation(conversation, clearMode);
            }
        });
    }

    private void onGetDescriptors(@NonNull List<Descriptor> descriptors) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetDescriptors: descriptors=" + descriptors);
        }

        mState |= GET_DESCRIPTORS_DONE;

        mTwinmeContext.getConversationService().getReplyTos(descriptors);

        Set<UUID> peerTwincodes = null;
        for (Descriptor descriptor : descriptors) {
            if (!mIsGroup) {
                continue;
            }

            UUID twincodeOutboundId = descriptor.getTwincodeOutboundId();
            if (twincodeOutboundId.equals(mTwincodeOutboundId)) {
                continue;
            }

            if (!mMembers.containsKey(twincodeOutboundId)) {
                if (peerTwincodes == null) {
                    peerTwincodes = new HashSet<>();
                }
                peerTwincodes.add(descriptor.getTwincodeOutboundId());
            }
        }

        if (descriptors.size() < MAX_OBJECTS) {
            mGetDescriptorsDone = true;
        }

        if (peerTwincodes != null) {
            mDescriptors = descriptors;
            if (mMemberTwincodes == null) {
                mMemberTwincodes = new ArrayList<>();
            }
            mMemberTwincodes.addAll(peerTwincodes);
            mState &= ~(LIST_OTHER_MEMBER | LIST_OTHER_MEMBER_DONE);
            onOperation();

            if (mContact != null) {
                mState |= LIST_OTHER_MEMBER;
                mTwinmeContext.listMembers(mContact, mMemberTwincodes, this::onListGroupMember);
            }

        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDescriptors(descriptors);
                }
            });
        }
    }

    /**
     *
     */
    private void getReplyAndRun(Descriptor descriptor, TwinmeContext.Consumer<Descriptor> uiConsumer) {
        if (descriptor.getReplyToDescriptorId() != null && descriptor.getReplyToDescriptor() == null) {
            mTwinmeContext.getDescriptor(descriptor.getReplyToDescriptorId(), (ErrorCode errorCode, Descriptor replyTo) -> {
                if (errorCode == ErrorCode.SUCCESS) {
                    descriptor.setReplyToDescriptor(replyTo);
                }
                runOnUiThread(() -> uiConsumer.accept(descriptor));
            });
        } else {
            runOnUiThread(() -> uiConsumer.accept(descriptor));
        }
    }

    private void onPushDescriptor(@Nullable Integer operationId, @NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPushDescriptor: descriptor=" + descriptor);
        }

        // If this was a push file, handle the next file or notify we are done.
        if (operationId != null && operationId == PUSH_FILE) {
            nextPushFile();
        }
        getReplyAndRun(descriptor, (Descriptor d) -> {
            if (mObserver != null) {
                mObserver.onPushDescriptor(d);
            }
        });
    }

    private void nextPushFile() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextPushFile");
        }

        synchronized (this) {
            mCurrentFile = null;
            if (mFiles != null && !mFiles.isEmpty()) {
                mCurrentFile = mFiles.remove(0);
                mState &= ~PUSH_FILE;
            }
        }
        if (mCurrentFile == null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onSendFilesFinished();
                }
            });
        }
    }

    private void onPopDescriptor(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPopDescriptor: descriptor=" + descriptor);
        }

        UUID twincodeOutboundId = descriptor.getTwincodeOutboundId();
        if (mIsGroup && mContact != null && !mMembers.containsKey(twincodeOutboundId)) {
            // The contact could be a group or a twinroom.
            mTwinmeContext.getGroupMember(mContact, twincodeOutboundId, (ErrorCode status, GroupMember groupMember) -> {
                if (status == ErrorCode.SUCCESS && groupMember != null) {
                    mMembers.put(twincodeOutboundId, groupMember);
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onGetGroupMembers(new ArrayList<>(mMembers.values()));
                        }
                    });
                }

                getReplyAndRun(descriptor, (Descriptor d) -> {
                    if (mObserver != null) {
                        mObserver.onPopDescriptor(d);
                    }
                });
            });
        } else {
            getReplyAndRun(descriptor, (Descriptor d) -> {
                if (mObserver != null) {
                    mObserver.onPopDescriptor(d);
                }
            });
        }
    }

    private void onUpdateDescriptor(@NonNull Descriptor descriptor, UpdateType updateType) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateDescriptor: descriptor=" + descriptor + " updateType=" + updateType);
        }

        getReplyAndRun(descriptor, (Descriptor d) -> {
            if (mObserver != null) {
                mObserver.onUpdateDescriptor(d, updateType);
            }
        });
    }

    private void onMarkDescriptorRead(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMarkDescriptorRead: descriptor=" + descriptor);
        }

        getReplyAndRun(descriptor, (Descriptor d) -> {
            if (mObserver != null) {
                mObserver.onMarkDescriptorRead(d);
            }
        });
    }

    private void onMarkDescriptorDeleted(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMarkDescriptorDeleted: descriptor=" + descriptor);
        }

        getReplyAndRun(descriptor, (Descriptor d) -> {
            if (mObserver != null) {
                mObserver.onMarkDescriptorDeleted(d);
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

        // If this is a push file, we are done with the current file.
        if (operationId == PUSH_FILE) {
            nextPushFile();
        }

        if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            switch (operationId) {
                case GET_CONTACT:
                case GET_GROUP:
                case LIST_GROUP_MEMBER:
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

                case MARK_DESCRIPTOR_READ:
                case MARK_DESCRIPTOR_DELETED:
                case DELETE_DESCRIPTOR:
                case SAVE_GEOLOCATION_MAP:
                case PUSH_OBJECT: // ITEM_NOT_FOUND raised if the replyTo is not found, message is not sent.
                case PUSH_FILE:
                case UPDATE_DESCRIPTOR:
                    return;

                default:
                    break;
            }
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

    private void onDeleteDescriptors(@NonNull DescriptorId[] descriptorList) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor: descriptor=" + descriptorList.length);
        }

        Set<DescriptorId> descriptorIdSet = new HashSet<>(Arrays.asList(descriptorList));
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteDescriptors(descriptorIdSet);
            }
        });
    }
}
