/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.notificationCenter;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.installations.FirebaseInstallations;
import com.google.firebase.messaging.FirebaseMessaging;

import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinlife.ManagementService;
import org.twinlife.twinme.TwinmeContext;

public class FirebasePushTokenManager {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "FirebasePushTokenManager";

    @NonNull
    public static final FirebasePushTokenManager INSTANCE = new FirebasePushTokenManager();

    private FirebasePushTokenManager() {
    }

    public void createToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<String> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createToken: context=" + context + " consumer=" + consumer);
        }

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    Log.e(LOG_TAG, "Firebase error while getting token", task.getException());
                    consumer.accept(null);
                    return;
                }

                // Get new Instance ID token
                String pushNotificationToken = task.getResult();
                consumer.accept(pushNotificationToken);
            });
        } catch (Exception exception) {
            // Exceptions can be raised because we cannot trust FCM.
            Log.e(LOG_TAG, "Firebase exception while creating token", exception);
            consumer.accept(null);
        }
    }

    public void deleteToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteToken: context=" + context + " consumer=" + consumer);
        }

        FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener(task -> {
            ErrorCode errorCode;
            Exception deleteException = task.getException();
            if (!task.isSuccessful() || deleteException != null) {
                Log.e(LOG_TAG, "Firebase error while deleting token", deleteException);
                errorCode = ErrorCode.LIBRARY_ERROR;
            } else {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Firebase token deleted");
                }
                errorCode = ErrorCode.SUCCESS;
            }

            consumer.accept(errorCode);
        });
    }

    public void deleteInstallation(@NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteInstallation: consumer=" + consumer);
        }

        try {
            FirebaseInstallations.getInstance().delete().addOnCompleteListener(task -> {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Firebase installation is deleted");
                }

                consumer.accept(ErrorCode.SUCCESS);
            });

        } catch (Exception exception) {
            // Exceptions can be raised because we cannot trust FCM.
            Log.w(LOG_TAG, "Firebase error while deleting installation", exception);

            consumer.accept(ErrorCode.LIBRARY_ERROR);
        }
    }

    @NonNull
    public String getPushProvider() {
        return ManagementService.PUSH_NOTIFICATION_FIREBASE_VARIANT;
    }
}
