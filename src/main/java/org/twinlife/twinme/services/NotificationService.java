/*
 *  Copyright (c) 2017-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.Notification;
import org.twinlife.twinlife.NotificationService.NotificationStat;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NotificationService extends AbstractTwinmeService {
    private static final String LOG_TAG = "NotificationService";
    private static final boolean DEBUG = false;

    private static final int MAX_NOTIFICATIONS = 10000;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_NOTIFICATIONS = 1 << 2;
    private static final int GET_NOTIFICATIONS_DONE = 1 << 3;
    private static final int GET_GROUP_MEMBER = 1 << 4;
    private static final int GET_GROUP_MEMBER_DONE = 1 << 5;
    private static final int GET_PENDING_NOTIFICATIONS = 1 << 6;
    private static final int GET_PENDING_NOTIFICATIONS_DONE = 1 << 7;
    private static final int ACKNOWLEDGE_NOTIFICATION = 1 << 8;
    private static final int DELETE_NOTIFICATION = 1 << 9;

    public interface Observer extends AbstractTwinmeService.Observer, CurrentSpaceObserver {

        void onGetNotifications(@NonNull List<Notification> notifications,
                                @NonNull Map<UUID, GroupMember> groupMembers);

        void onAddNotification(@NonNull Notification notification, @Nullable GroupMember groupMember);

        void onAcknowledgeNotification(@NonNull Notification notification);

        void onDeleteNotification(@NonNull UUID notificationId);

        void onUpdatePendingNotifications(boolean hasPendingNotification);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onAddNotification(@NonNull Notification notification) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onAddNotification: notification=" + notification);
            }

            if (!mTwinmeContext.isCurrentSpace(notification.getSubject())) {

                return;
            }

            NotificationService.this.onAddNotification(notification);
        }

        @Override
        public void onAcknowledgeNotification(long requestId, @NonNull Notification notification) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onAcknowledgeNotification: requestId=" + requestId + " notification=" + notification);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            NotificationService.this.onAcknowledgeNotification(notification);
        }

        @Override
        public void onDeleteNotification(long requestId, @NonNull UUID notificationId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteNotification: requestId=" + requestId + " notificationId=" + notificationId);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            NotificationService.this.onDeleteNotification(notificationId);
        }

        @Override
        public void onUpdatePendingNotifications(long requestId, boolean hasPendingNotifications) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdatePendingNotifications: requestId=" + requestId + " hasPendingNotifications=" + hasPendingNotifications);
            }

            NotificationService.this.onUpdatePendingNotifications(hasPendingNotifications);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            NotificationService.this.onSetCurrentSpace(space);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private Space mSpace;
    private final Map<UUID, GroupMember> mGroupMembers;
    private List<Notification> mNotifications;
    private final Map<UUID, Group> mPendingGroupMembers;

    public NotificationService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "NotificationService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mGroupMembers = new HashMap<>();
        mPendingGroupMembers = new HashMap<>();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void getNotifications() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getNotifications");
        }

        mState &= ~(GET_NOTIFICATIONS | GET_NOTIFICATIONS_DONE | GET_GROUP_MEMBER | GET_GROUP_MEMBER_DONE);
        mNotifications = null;
        startOperation();
    }

    public void acknowledgeNotification(@NonNull Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "acknowledgeNotification: notification=" + notification);
        }

        long requestId = newOperation(ACKNOWLEDGE_NOTIFICATION);
        if (DEBUG) {
            Log.d(LOG_TAG, "NotificationService.acknowledgeNotification: requestId=" + requestId + " notification=" + notification);
        }

        mTwinmeContext.acknowledgeNotification(requestId, notification);
    }

    public void deleteNotification(@NonNull Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteNotification: notification=" + notification);
        }

        long requestId = newOperation(DELETE_NOTIFICATION);
        if (DEBUG) {
            Log.d(LOG_TAG, "NotificationService.deleteNotification: requestId=" + requestId + " notification=" + notification);
        }

        mTwinmeContext.deleteNotification(requestId, notification);
    }

    //
    // Private methods
    //

    private void onGetNotifications(@NonNull List<Notification> notifications) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetNotifications");
        }

        mState |= GET_NOTIFICATIONS_DONE;
        mNotifications = notifications;
        for (Notification notification : notifications) {
            final DescriptorId descriptorId = notification.getDescriptorId();
            if (descriptorId != null && (notification.getSubject() instanceof Group)
                    && !mGroupMembers.containsKey(descriptorId.twincodeOutboundId)
                    && !mPendingGroupMembers.containsKey(descriptorId.twincodeOutboundId)) {
                mPendingGroupMembers.put(descriptorId.twincodeOutboundId, (Group) notification.getSubject());
            }
        }
        if (mPendingGroupMembers.isEmpty()) {
            mState |= GET_GROUP_MEMBER | GET_GROUP_MEMBER_DONE;
        }
        onOperation();
    }

    private void onGetGroupMember(@NonNull ErrorCode errorCode, @Nullable GroupMember groupMember) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroupMember errorCode=" + errorCode + " groupMember=" + groupMember);
        }

        mState |= GET_GROUP_MEMBER_DONE;
        if (errorCode == ErrorCode.SUCCESS && groupMember != null) {
            mGroupMembers.put(groupMember.getId(), groupMember);
        }
        if (!mPendingGroupMembers.isEmpty()) {
            mState &= ~(GET_GROUP_MEMBER | GET_GROUP_MEMBER_DONE);
        }
        onOperation();
    }

    @Override
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (BuildConfig.ENABLE_CHECKS && Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(247));
        }
        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: get the current space.
        //

        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            long requestId = newOperation(GET_CURRENT_SPACE);
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.getCurrentSpace: requestId=" + requestId);
            }

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                mSpace = space;
                mState |= GET_CURRENT_SPACE_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 1
        //

        if ((mState & GET_NOTIFICATIONS) == 0) {
            mState |= GET_NOTIFICATIONS;

            // Filter to keep notifications of the current space.
            final Filter<Notification> filter = new Filter<>(mSpace);
            mTwinmeContext.findNotifications(filter, MAX_NOTIFICATIONS, this::onGetNotifications);
            return;
        }
        if ((mState & GET_NOTIFICATIONS_DONE) == 0) {
            return;
        }

        if ((mState & GET_GROUP_MEMBER) == 0) {
            mState |= GET_GROUP_MEMBER;

            Map.Entry <UUID, Group> next = mPendingGroupMembers.entrySet().iterator().next();
            mPendingGroupMembers.remove(next.getKey());
            mTwinmeContext.getGroupMember(next.getValue(), next.getKey(), this::onGetGroupMember);
            return;
        }
        if ((mState & GET_GROUP_MEMBER_DONE) == 0) {
            return;
        }

        if (mNotifications != null) {
            final List<Notification> notifications = mNotifications;
            mNotifications = null;
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetNotifications(notifications, mGroupMembers);

                    // Check if we have at least one notification that is not acknowledged.
                    boolean hasPendingNotifications = false;
                    for (Notification notification : notifications) {
                        if (!notification.isAcknowledged()) {
                            hasPendingNotifications = true;
                            break;
                        }
                    }
                    mObserver.onUpdatePendingNotifications(hasPendingNotifications);
                }
            });
        }

        //
        // Step 2
        //

        if ((mState & GET_PENDING_NOTIFICATIONS) == 0) {
            mState |= GET_PENDING_NOTIFICATIONS;

            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.getSpaceNotificationStats");
            }
            mTwinmeContext.getSpaceNotificationStats(this::onGetSpaceNotificationStats);
            return;
        }
        if ((mState & GET_PENDING_NOTIFICATIONS_DONE) == 0) {
            return;
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    private void onAddNotification(@NonNull Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAddNotification: notification=" + notification);
        }

        if (notification.getSubject() instanceof Group && notification.getDescriptorId() != null) {
            mTwinmeContext.getGroupMember((Group) notification.getSubject(), notification.getDescriptorId().twincodeOutboundId,
                    (ErrorCode errorCode, GroupMember groupMember) -> runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onAddNotification(notification, groupMember);
                        }
                    }));
        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onAddNotification(notification, null);
                }
            });
        }
    }

    private void onAcknowledgeNotification(@NonNull Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAcknowledgeNotification: notification=" + notification);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onAcknowledgeNotification(notification);
            }
        });
    }

    private void onDeleteNotification(@NonNull UUID notificationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteNotification: notificationId=" + notificationId);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteNotification(notificationId);
            }
        });
    }

    private void onGetSpaceNotificationStats(@NonNull ErrorCode errorCode, @Nullable NotificationStat notificationStat) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetSpaceNotificationStats: notificationStat=" + notificationStat);
        }

        mState |= GET_PENDING_NOTIFICATIONS_DONE;
        if (notificationStat != null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onUpdatePendingNotifications(notificationStat.getPendingCount() > 0);
                }
            });
        }
        onOperation();
    }

    private void onUpdatePendingNotifications(boolean hasPendingNotifications) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdatePendingNotifications: hasPendingNotifications=" + hasPendingNotifications);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdatePendingNotifications(hasPendingNotifications);
            }
        });
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        if (mSpace != space) {
            mSpace = space;
            mState &= ~(GET_NOTIFICATIONS | GET_NOTIFICATIONS_DONE);
            mState &= ~(GET_PENDING_NOTIFICATIONS | GET_PENDING_NOTIFICATIONS_DONE);
        }
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }
}
