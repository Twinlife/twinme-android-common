/*
 *  Copyright (c) 2018-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessaging;

import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConnectionStatus;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.GroupConversation;
import org.twinlife.twinlife.ConversationService.InvitationDescriptor;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.ManagementService;
import org.twinlife.twinlife.RepositoryService;
import org.twinlife.twinlife.Twinlife;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinme.FeatureUtils;
import org.twinlife.twinme.calls.telecom.TelecomUtils;
import org.twinlife.twinme.ui.TwinmeApplication;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.utils.update.LastVersion;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The AdminService handles several application house keeping:
 * - detecting server connection changes and notifying the application (and the current activity),
 * - getting the JSON file for new versions,
 * - updating stats,
 * - cleaning groups when they are removed,
 * - cleaning unused temporary files.
 */
public class AdminService {
    private static final String LOG_TAG = "AdminService";
    private static final boolean DEBUG = false;

    private static final int DELETE_GROUP = 1 << 1;
    private static final int GET_JOINED_GROUP = 1 << 2;
    private static final int GROUP_CONVERSATION_DELETED = 1 << 3;
    private static final int UPDATE_SCORES = 1 << 4;
    private static final int GET_DEFAULT_PROFILE = 1 << 5;

    private static final String UPDATE_SCORE_PREFERENCES = "UpdateScores";
    private static final String UPDATE_SCORE_DATE = "lastUpdateDate";
    private static final long MIN_UPDATE_SCORE_DELAY = 24 * 3600 * 1000; // 24 hours in ms
    private static final long CHECK_LAST_VERSION_DELAY = 10 * 1000; // 10s after online

    private class TwinmeContextObserver extends TwinmeContext.DefaultObserver {

        @Override
        public void onTwinlifeReady() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeReady");
            }

            AdminService.this.onTwinlifeReady();
        }

        @Override
        public void onTwinlifeOnline() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeOnline");
            }

            AdminService.this.onTwinlifeOnline();
        }

        @Override
        public void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onConnectionStatusChange " + connectionStatus);
            }

            AdminService.this.onConnectionStatusChange(connectionStatus);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }
            AdminService.this.onSetCurrentSpace(space);
        }

        @Override
        public void onDeleteGroup(long requestId, @NonNull UUID groupId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteGroup: requestId=" + requestId + " groupId=" + groupId);
            }
            Integer operationId;
            synchronized (mRequestIds) {
                operationId = mRequestIds.remove(requestId);
            }
            if (operationId != null) {
                AdminService.this.onDeleteGroup(requestId, groupId);
            }
        }

        @Override
        public void onUpdateStats(long requestId, @NonNull List<Contact> updatedContacts, @NonNull List<Group> updatedGroups) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateStats: requestId=" + requestId + " updatedContacts=" + updatedContacts + " updatedGroups=" + updatedGroups);
            }

            Integer operationId;
            synchronized (mRequestIds) {
                operationId = mRequestIds.remove(requestId);
            }
            if (operationId != null) {
                AdminService.this.onUpdateScores(requestId, updatedContacts, updatedGroups);
            }
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId;
            synchronized (mRequestIds) {
                operationId = mRequestIds.remove(requestId);
            }
            if (operationId != null) {
                AdminService.this.onError(requestId, operationId, errorCode, errorParameter);
            }
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onJoinGroupResponse(long requestId, @NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onJoinGroupResponse: requestId=" + requestId + " conversation=" + conversation);
            }

            AdminService.this.onJoinGroupResponse(conversation, invitation);
        }

        @Override
        public void onDeleteGroupConversation(@NonNull UUID conversationId, @NonNull UUID groupId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteGroupConversation: conversationId=" + conversationId
                        + " groupId=" + groupId);
            }

            AdminService.this.onDeleteGroupConversation(conversationId, groupId);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId;
            synchronized (mRequestIds) {
                operationId = mRequestIds.remove(requestId);
                if (operationId == null) {

                    return;
                }
            }

            AdminService.this.onError(requestId, operationId, errorCode, errorParameter);
        }
    }


    @NonNull
    private final TwinmeContext mTwinmeContext;
    private Space mCurrentSpace;
    @Nullable
    private LastVersion mLastVersion;

    @SuppressLint("UseSparseArrays")
    private final Map<Long, Integer> mRequestIds = new HashMap<>();
    private boolean mRestarted = false;
    private final ConversationServiceObserver mConversationServiceObserver;
    @NonNull
    private final TwinmeApplication mApplication;

    static final class GroupMemberOperation {
        final UUID conversationId;
        UUID groupTwincodeId;
        UUID memberTwincodeId;
        UUID groupId;
        int operation;
        final long requestId;
        InvitationDescriptor invitation;

        GroupMemberOperation(long requestId, int operation, UUID conversationId, InvitationDescriptor invitation) {
            this.requestId = requestId;
            this.operation = operation;
            this.invitation = invitation;
            this.groupTwincodeId = invitation.getGroupTwincodeId();
            this.memberTwincodeId = invitation.getMemberTwincodeId();
            this.conversationId = conversationId;
        }

        GroupMemberOperation(long requestId, int operation, UUID conversationId, UUID groupId) {
            this.requestId = requestId;
            this.operation = operation;
            this.conversationId = conversationId;
            this.groupId = groupId;
        }
    }

    @SuppressLint("UseSparseArrays")
    private final HashMap<Long, GroupMemberOperation> mPendingOperations = new HashMap<>();

    public AdminService(@NonNull TwinmeContext twinmeContext, @NonNull TwinmeApplication application) {
        if (DEBUG) {
            Log.d(LOG_TAG, "AdminService: twinmeContext=" + twinmeContext + " application=" + application);
        }

        mTwinmeContext = twinmeContext;

        TwinmeContextObserver twinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();

        mApplication = application;
        mTwinmeContext.setObserver(twinmeContextObserver);
    }

    public Space getCurrentSpace() {

        return mCurrentSpace;
    }

    @Nullable
    public LastVersion getLastVersion() {

        return mLastVersion != null && mLastVersion.isValid() ? mLastVersion : null;
    }

    public void setLastVersion(@Nullable LastVersion lastVersion) {
        mLastVersion = lastVersion;
    }

    //
    // Private methods
    //

    /**
     * Group invitation step 3: we are now member of the group, get the group information before the notification.
     *
     * @param conversation the group conversation.
     * @param invitation   the invitation.
     */
    private void onJoinGroupResponse(@NonNull GroupConversation conversation, @Nullable InvitationDescriptor invitation) {

        if (invitation != null) {
            long requestId = newOperation(GET_JOINED_GROUP);
            mPendingOperations.put(requestId, new GroupMemberOperation(requestId, GET_JOINED_GROUP, conversation.getId(), invitation));
            mTwinmeContext.getGroup(conversation.getContactId(), (ErrorCode errorCode, Group group) -> onGetGroup(requestId, group));
        }
    }

    /**
     * Group invitation step 4: notify the user.
     *
     * @param requestId the getGroup() requestId.
     * @param group     the group.
     */
    private void onGetGroup(long requestId, @NonNull Group group) {
        final GroupMemberOperation operation = mPendingOperations.remove(requestId);
        if (operation != null) {
            switch (operation.operation) {
                case GROUP_CONVERSATION_DELETED:
                    // The group conversation was deleted, delete the group object and group member twincode.
                    if (operation.groupId.equals(group.getId()) && !group.isDeleted()) {
                        requestId = newOperation(DELETE_GROUP);
                        operation.operation = DELETE_GROUP;
                        mPendingOperations.put(requestId, operation);
                        mTwinmeContext.deleteGroup(requestId, group);
                    }
                    break;

                case GET_JOINED_GROUP:
                    if (operation.memberTwincodeId.equals(group.getTwincodeOutboundId())) {
                        EventMonitor.info(LOG_TAG, "Now member of ", group.getName(), "(",
                                group.getGroupTwincodeOutboundId(), ") as ", group.getTwincodeOutboundId());

                        Conversation conversation = mTwinmeContext.getConversationService().getConversation(group);
                        mTwinmeContext.getNotificationCenter().onJoinGroup(group, conversation);
                    }
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * A group conversation was removed and we are no longer member of the group.
     * This is either a leaveGroup() operation that we made previously or a leave-group message
     * that we received from another member.
     *
     * @param conversationId the group conversation id.
     * @param groupId        the local group id.
     */
    private void onDeleteGroupConversation(@NonNull UUID conversationId, @NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteGroupConversation: conversationId=" + conversationId + " groupId=" + groupId);
        }

        // Get the group to proceed to the final group cleanup.
        long requestId = newOperation(GROUP_CONVERSATION_DELETED);
        mPendingOperations.put(requestId, new GroupMemberOperation(requestId, GROUP_CONVERSATION_DELETED, conversationId, groupId));
        mTwinmeContext.getGroup(groupId, (ErrorCode errorCode, Group group) -> {
            mRequestIds.remove(requestId);
            onGetGroup(requestId, group);
        });
    }

    /**
     * Acknowledge for a group deletion.
     *
     * @param requestId
     * @param groupId
     */
    private void onDeleteGroup(long requestId, @NonNull UUID groupId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteGroup: requestId=" + requestId + " groupId=" + groupId);
        }

        mPendingOperations.remove(requestId);
    }

    private long newOperation(int operationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "newOperation: operationId=" + operationId);
        }

        long requestId = mTwinmeContext.newRequestId();
        synchronized (mRequestIds) {
            mRequestIds.put(requestId, operationId);
        }

        return requestId;
    }

    private void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }
        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);

        if (mApplication instanceof Application) {
            Application app = (Application) mApplication;
            if (FeatureUtils.isTelecomSupported(app)) {
                TelecomUtils.registerPhoneAccount(app);
            }
        } else {
            Log.w(LOG_TAG, "mApplication " + mApplication + " is not an Application, this should not happen");
        }

        // Build and setup the group weight table to update the group usage score.
        //  - points are added when a contact/group is used,
        //  - scale factor is applied on other contact/groups to reduce their points.
        // We should have:
        //    1.0 >= scale  > 0.0
        //           points > 0.0
        RepositoryService repositoryService = mTwinmeContext.getRepositoryService();

        int count = RepositoryService.StatType.values().length;
        RepositoryService.Weight[] groupWeight = new RepositoryService.Weight[count];
        for (int i = 0; i < count; i++) {
            groupWeight[i] = new RepositoryService.Weight(0.5, 0.97);
        }
        repositoryService.setWeightTable(Group.SCHEMA_ID, groupWeight);

        // Build and setup the contact weight table to update the group usage score.
        RepositoryService.Weight[] contactWeight = new RepositoryService.Weight[count];
        for (int i = 0; i < count; i++) {
            contactWeight[i] = new RepositoryService.Weight(1.0, 0.98);
        }
        repositoryService.setWeightTable(Contact.SCHEMA_ID, contactWeight);

        // Schedule the update score once per day.
        SharedPreferences preferences = mApplication.getSharedPreferences(UPDATE_SCORE_PREFERENCES, Context.MODE_PRIVATE);
        long delay = MIN_UPDATE_SCORE_DELAY;
        if (preferences != null) {
            long lastUpdateScoreDate = preferences.getLong(UPDATE_SCORE_DATE, 0);
            if (lastUpdateScoreDate > 0) {
                delay = lastUpdateScoreDate + MIN_UPDATE_SCORE_DELAY - System.currentTimeMillis();
            }
        }

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    Log.w(LOG_TAG, "Cannot get Firebase token: " + task.getException());
                    return;
                }

                // Get new Instance ID token
                String pushNotificationToken = task.getResult();
                mTwinmeContext.getManagementService().setPushNotificationToken(ManagementService.PUSH_NOTIFICATION_FIREBASE_VARIANT, pushNotificationToken);
            });

        } catch (Exception exception) {
            // Exceptions can be raised because we cannot trust FCM.
            Log.w(LOG_TAG, "Firebase exception: " + exception.getMessage());
        }

        if (mLastVersion == null) {
            mLastVersion = mApplication.getLastVersion();
        }

        Utils.cleanupTemporaryDirectory(mApplication.getCacheDir());
        final File dir = new File(mApplication.getFilesDir(), Twinlife.OLD_TMP_DIR);
        if (dir.exists()) {
            Utils.cleanupTemporaryDirectory(dir);
        }

        mTwinmeContext.getJobService().scheduleIn("Update scores", this::updateScoreJob, delay, JobService.Priority.REPORT);
    }

    private void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;
        }

        // Don't try to download the JSON version on Android 4.1 and 4.2 since there is no ISRG Root X1
        // certificat to verify the SSL connection.
        if (mLastVersion != null && mLastVersion.needUpdate()) {
            mTwinmeContext.getJobService().scheduleIn("Check version", this::checkLatestVersionJob, CHECK_LAST_VERSION_DELAY, JobService.Priority.REPORT);
        }
    }

    private void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionStatusChange " + connectionStatus);
        }

        mApplication.onConnectionStatusChange(connectionStatus);
    }

    private void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace");
        }

        mCurrentSpace = space;
    }

    private void checkLatestVersionJob() {
        if (DEBUG) {
            Log.d(LOG_TAG, "checkLatestVersionJob");
        }

        if (mLastVersion != null && mLastVersion.needUpdate()) {
            mApplication.checkLastVersion(mLastVersion);
        }
    }

    private void updateScoreJob() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateScoreJob");
        }

        long requestId = newOperation(UPDATE_SCORES);
        mTwinmeContext.updateScores(requestId, true);
    }

    private void onUpdateScores(long requestId, @NonNull List<Contact> updatedContacts, @NonNull List<Group> updatedGroups) {
        if (DEBUG) {
            Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateStats: requestId=" + requestId + " updatedContacts=" + updatedContacts + " updatedGroups=" + updatedGroups);
        }

        SharedPreferences preferences = mApplication.getSharedPreferences(UPDATE_SCORE_PREFERENCES, Context.MODE_PRIVATE);
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putLong(UPDATE_SCORE_DATE, System.currentTimeMillis());
            editor.apply();
        }

        mTwinmeContext.getJobService().scheduleIn("Update scores", this::updateScoreJob, MIN_UPDATE_SCORE_DELAY, JobService.Priority.REPORT);
    }

    private void onError(long requestId, int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }
        if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            mPendingOperations.remove(requestId);
            switch (operationId) {
                case GROUP_CONVERSATION_DELETED:
                case GET_DEFAULT_PROFILE:
                    return;

                default:
                    break;
            }
        }

        mTwinmeContext.assertion(ServiceAssertPoint.ON_ERROR, AssertPoint.create(getClass())
                .put(errorCode).putOperationId(operationId));
    }
}
