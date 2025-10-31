/*
 *  Copyright (c) 2016-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.ManagementService;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinme.TwinmeApplication;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.NotificationContent;
import org.twinlife.twinme.models.Originator;

import java.util.HashMap;

public class TwinmeFirebaseMessagingService extends FirebaseMessagingService {
    private static final String LOG_TAG = "TwinmeFirebaseMessag...";
    private static final boolean DEBUG = false;
    private static final int RETRY_PERIOD = 5000; // ms

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMessageReceived: message=" + message);
        }

        Context context = getApplicationContext();
        TwinmeApplicationImpl twinmeApplication = TwinmeApplicationImpl.getInstance(context);
        if (twinmeApplication == null) {

            return;
        }

        final JobService.ProcessingLock lock = twinmeApplication.allocateProcessingLock();
        try {
            TwinmeContext twinmeContext = twinmeApplication.getTwinmeContext();
            if (twinmeContext != null) {
                EventMonitor.event(LOG_TAG, "Firebase message");

                // Critical: notify twinme about the system notification to decode it and verify that the notification is valid for us.
                NotificationContent notificationContent = twinmeContext.systemNotification(context, message.getData());
                if (notificationContent != null) {
                    // Start the PeerService as a foreground service to keep the application running
                    // until we get the messages or the incoming call connection.
                    PeerService.startService(context, message.getPriority(), message.getSentTime());
                }
            }
        } finally {
            lock.release();
        }
    }

    @Override
    public void onDeletedMessages() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeletedMessages");
        }

        Context context = getApplicationContext();
        TwinmeApplication twinmeApplication = TwinmeApplicationImpl.getInstance(context);
        if (twinmeApplication == null) {
            return;
        }
        final TwinmeContext twinmeContext = twinmeApplication.getTwinmeContext();
        if (twinmeContext != null) {
            final HashMap<String, String> data = new HashMap<>();
            data.put("notification-type", "sync-required");
            twinmeContext.systemNotification(getApplicationContext(), data);
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onNewToken");
        }

        Context context = getApplicationContext();
        TwinmeApplication twinmeApplication = TwinmeApplicationImpl.getInstance(context);
        if (twinmeApplication != null) {
            TwinmeContext twinmeContext = twinmeApplication.getTwinmeContext();
            if (twinmeContext != null && twinmeContext.hasTwinlife()) {
                twinmeContext.getManagementService().setPushNotificationToken(ManagementService.PUSH_NOTIFICATION_FIREBASE_VARIANT, token);
                return;
            }
        }

        Handler mHandler = new Handler(Looper.getMainLooper());
        mHandler.postDelayed(() -> onNewToken(token), RETRY_PERIOD);
    }

    @Nullable
    private Bitmap getAvatar(@NonNull TwinmeContext twinmeContext, @NonNull Originator originator) {
        if (originator.getAvatarId() == null) {
            return null;
        }
        return twinmeContext.getImageService().getImage(originator.getAvatarId(), ImageService.Kind.THUMBNAIL);
    }
}
