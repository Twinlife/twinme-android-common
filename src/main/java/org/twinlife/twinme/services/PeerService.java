/*
 *  Copyright (c) 2018-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.NotificationCenter;
import org.twinlife.twinme.TwinmeApplication;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.calls.CallService;
import org.twinlife.twinme.ui.Intents;
import org.twinlife.twinme.TwinmeApplicationImpl;

import java.util.concurrent.ScheduledFuture;

/**
 * Peer foreground connection service.
 */
public class PeerService extends Service implements JobService.Observer {
    private static final String LOG_TAG = "PeerService";
    private static final boolean DEBUG = false;

    private static final int SERVICE_DELAY_MS = 25 * 1000;

    public static final String START_FOREGROUND = "start";
    public static final String STOP_FOREGROUND = "stop";

    private static volatile boolean sIsRunning = false;
    private static boolean sTransferringData = false;

    @Nullable
    private TwinmeContext mTwinmeContext;
    @Nullable
    private NotificationCenter mNotificationCenter;
    @Nullable
    private JobService mJobService;
    @Nullable
    private JobService.Job mExpire;
    private int mNotificationId = 0;
    @Nullable
    private JobService.ProcessingLock mProcessingLock;
    private long mStartTime = -1;
    @Nullable
    private ScheduledFuture<?> mStopService;

    public static void forceStop(@NonNull Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "forceStop isRunning=" + sIsRunning);
        }

        if (sIsRunning) {
            Intent intent = new Intent(context, PeerService.class);

            intent.setAction(PeerService.STOP_FOREGROUND);

            sTransferringData = false;
            try {
                // With Android 9, if the user forbids the application to start a foreground service when it is in background,
                // starting the PeerService will proceed, but we are not aware of the problem: the foreground service is
                // simply ignored and has no effect on keeping the application running.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (RuntimeException ex) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "startForegroundService error: ", ex);
                }
            }
        }
    }

    public static void startService(@NonNull Context context, int priority, long sentTime) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startService");
        }

        if (!sIsRunning) {
            Intent intent = new Intent(context, PeerService.class);

            intent.putExtra(Intents.INTENT_PRIORITY, priority);
            intent.putExtra(Intents.INTENT_ORIGINAL_PRIORITY, priority);
            intent.putExtra(Intents.INTENT_SENT_TIME, sentTime);
            intent.setAction(PeerService.START_FOREGROUND);

            sTransferringData = priority > 0;
            try {

                // With Android 9, if the user forbids the application to start a foreground service when it is in background,
                // starting the PeerService will proceed, but we are not aware of the problem: the foreground service is
                // simply ignored and has no effect on keeping the application running.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (RuntimeException ex) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "startService error: ", ex);
                }
            }
        }
    }

    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreate");
        }

        mNotificationId = NotificationCenter.FOREGROUND_SERVICE_NOTIFICATION_ID;
        sIsRunning = true;
        mStartTime = System.currentTimeMillis();
        initialize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStartCommand");
        }

        // Call again startForegroundService (we have seen that sometimes, the first call is ignored).
        initialize();

        if (intent == null || intent.getAction() == null) {

            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        switch (action) {
            case START_FOREGROUND:
                onActionStart(intent);
                break;

            case STOP_FOREGROUND:
                onActionStop(intent);
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBind");
        }

        // We don't provide binding, so return null
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDestroy");
        }

        if (mProcessingLock != null) {
            mProcessingLock.release();
        }

        sIsRunning = false;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTimeout: startId=" + startId + " fgsType=" + fgsType);
        }

        if (mTwinmeContext != null) {
            long duration = -1;
            if (mStartTime != -1) {
                duration = System.currentTimeMillis() - mStartTime;
            }

            mTwinmeContext.assertion(ServiceAssertPoint.SERVICE_TIMEOUT, AssertPoint.createLength(duration));
        }

        finish();
    }

    @Override
    public void onEnterForeground() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onEnterForeground");
        }

        // We are now in foreground: we can stop the peer service since we don't need it anymore.
        finish();
    }

    @Override
    public void onEnterBackground() {

    }

    @Override
    public void onBackgroundNetworkStart() {

    }

    @Override
    public void onBackgroundNetworkStop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBackgroundNetworkStop");
        }

        // Wait 1s before asking the service to stop because sometimes a new P2P connection
        // is started 100 to 500ms after and we won't be able to start it again.
        if (mJobService != null) {
            mStopService = mJobService.schedule(this::stopPeerService, 1000);
        }
    }

    @Override
    public void onActivePeers(int count) {

        if (mNotificationCenter != null) {
            sTransferringData = count > 0;
            Notification notification = mNotificationCenter.createPeerServiceNotification(sTransferringData);
            startForegroundCompat(notification);
        }
    }

    private void onActionStart(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStart intent=" + intent);
        }

        EventMonitor.event("Start foreground service");

        // Tell the job service that a foreground service has started.
        // Note: we can start very quickly, before the Twinlife service is initialized!
        int priority = intent.getIntExtra(Intents.INTENT_PRIORITY, 0);
        int originalPriority = intent.getIntExtra(Intents.INTENT_ORIGINAL_PRIORITY, 0);
        long sentTime = intent.getLongExtra(Intents.INTENT_SENT_TIME, 0);

        if (mExpire != null) {
            mExpire.cancel();
            mExpire = null;
        }

        if (mJobService != null) {
            mExpire = mJobService.startForegroundService(priority, originalPriority, sentTime, this::onServiceExpire, SERVICE_DELAY_MS);
        }

        if (mTwinmeContext != null) {
            mTwinmeContext.connect();
        }

        // The Firebase notification started the PeerService but a call is already running.
        if (CallService.isRunning()) {
            forceStop(this);
        }
    }

    private void onActionStop(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStop intent=" + intent);
        }

        finish();
    }

    private void onServiceExpire() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onServiceExpire");
        }

        finish();
    }

    private void initialize() {
        if (DEBUG) {
            Log.d(LOG_TAG, "initialize");
        }

        if (mTwinmeContext == null) {

            TwinmeApplication twinmeApplication = TwinmeApplicationImpl.getInstance(this);
            if (twinmeApplication == null) {

                return;
            }

            mTwinmeContext = twinmeApplication.getTwinmeContext();
            if (mTwinmeContext == null) {

                return;
            }

            // Get the power processing lock to tell the system we need the CPU.
            mJobService = mTwinmeContext.getJobService();
            mJobService.setObserver(this);
            mProcessingLock = mJobService.allocateProcessingLock();
        }

        if (mNotificationCenter == null) {
            mNotificationCenter = mTwinmeContext.getNotificationCenter();
        }

        // Make the notification and call startForeground() as soon as possible: we use the "Transferring messages" notification.
        Notification notification = mNotificationCenter.createPeerServiceNotification(sTransferringData);
        startForegroundCompat(notification);
    }

    private void startForegroundCompat(@NonNull Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startForegroundCompat notification=" + notification);
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NotificationCenter.FOREGROUND_SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NotificationCenter.FOREGROUND_SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NotificationCenter.FOREGROUND_SERVICE_NOTIFICATION_ID, notification);
            }
        } catch (IllegalStateException|SecurityException ex) {
            if (Logger.ERROR) {
                Logger.error(LOG_TAG, "startForeground not allowed", ex);
            }

        } catch (Exception exception) {
            if (mTwinmeContext != null) {
                mTwinmeContext.exception(ServiceAssertPoint.START_PEER_SERVICE, exception, null);
            }
        }
    }

    private void stopPeerService() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopPeerService");
        }

        mStopService = null;

        final boolean isIdle = mJobService == null || mJobService.isIdle();
        if (isIdle) {
            finish();
        }
    }

    private void finish() {
        if (DEBUG) {
            Log.d(LOG_TAG, "finish");
        }

        EventMonitor.event("Foreground service is finished", mStartTime);
        if (mStopService != null) {
            mStopService.cancel(false);
            mStopService = null;
        }
        if (mExpire != null) {
            mExpire.cancel();
            mExpire = null;
        }

        if (mJobService != null) {
            mJobService.removeObserver(this);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationId > 0 && mNotificationCenter != null) {
            mNotificationCenter.cancel(mNotificationId);
        }
    }
}
