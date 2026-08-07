/*
 *  Copyright (c) 2026 twinlife SA.
 *
 *  All Rights Reserved.
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.huawei.hms.push.HmsMessageService;
import com.huawei.hms.push.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.ManagementService;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinme.TwinmeApplication;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.NotificationContent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HuaweiMessagingService extends HmsMessageService {
    private static final String LOG_TAG = "HuaweiMessagingService";
    private static final boolean DEBUG = false;
    private static final int RETRY_PERIOD = 5000;

    @Override
    public void onNewToken(String token) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onNewToken: token=" + token);
        }

        Context context = getApplicationContext();
        TwinmeApplication twinmeApplication = TwinmeApplicationImpl.getInstance(context);
        if (twinmeApplication != null) {
            TwinmeContext twinmeContext = twinmeApplication.getTwinmeContext();
            if (twinmeContext != null && twinmeContext.hasTwinlife()) {
                twinmeContext.execute(() -> twinmeContext.getManagementService().setPushNotificationToken(ManagementService.PUSH_NOTIFICATION_HUAWEI_VARIANT, token));
                return;
            }
        }

        Handler mHandler = new Handler(Looper.getMainLooper());
        mHandler.postDelayed(() -> onNewToken(token), RETRY_PERIOD);
    }

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

        TwinmeContext twinmeContext = twinmeApplication.getTwinmeContext();

        if (twinmeContext == null) {

            return;
        }

        final JobService.ProcessingLock lock = twinmeApplication.allocateProcessingLock();
        twinmeContext.execute(() -> {
            try {

                EventMonitor.event(LOG_TAG, "Huawei message");

                Map<String, String> payload = jsonToMap(message.getData());

                // Critical: notify twinme about the system notification to decode it and verify that the notification is valid for us.
                NotificationContent notificationContent = twinmeContext.systemNotification(context, payload);

                if (notificationContent != null) {
                    // Start the PeerService as a foreground service to keep the application running
                    // until we get the messages or the incoming call connection.
                    PeerService.startService(context, message.getUrgency(), message.getSentTime());
                }
            } catch (JSONException e) {
                Log.e(LOG_TAG, "Could not parse push notification payload: " + message.getData(), e);
            } finally {
                lock.release();
            }
        });
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
            twinmeContext.execute(() -> twinmeContext.systemNotification(getApplicationContext(), data));
        }
    }

    @NonNull
    private Map<String, String> jsonToMap(@NonNull String json) throws JSONException {

        Map<String, String> res = new HashMap<>();

        JSONObject object = new JSONObject(json);

        for (Iterator<String> it = object.keys(); it.hasNext(); ) {
            String key = it.next();
            res.put(key, object.getString(key));
        }

        return res;
    }
}
