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

import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.utils.PlatformSpecificUtils;

public class PushTokenManager {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PushTokenManager";

    @NonNull
    private String mPushProvider = FirebasePushTokenManager.INSTANCE.getPushProvider();

    @NonNull
    public static final PushTokenManager INSTANCE = new PushTokenManager();

    private PushTokenManager() {
    }

    public void createToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<String> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createToken: context=" + context + " consumer=" + consumer);
        }

        if (PlatformSpecificUtils.isGooglePlayServicesAvailable(context)) {
            FirebasePushTokenManager.INSTANCE.createToken(context, (token) -> {
                if (token != null) {
                    mPushProvider = FirebasePushTokenManager.INSTANCE.getPushProvider();

                    consumer.accept(token);
                } else {
                    huaweiFallback(context, consumer);
                }
            });
        } else {
            huaweiFallback(context, consumer);
        }
    }

    private void huaweiFallback(@NonNull Context context, @NonNull TwinmeContext.Consumer<String> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "huaweiFallback: context=" + context + " consumer=" + consumer);
        }
        mPushProvider = HuaweiPushTokenManager.INSTANCE.getPushProvider();

        HuaweiPushTokenManager.INSTANCE.createToken(context, consumer);
    }

    public void deleteToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteToken: context=" + context + " consumer=" + consumer);
        }

        if (FirebasePushTokenManager.INSTANCE.getPushProvider().equals(mPushProvider)) {
            FirebasePushTokenManager.INSTANCE.deleteToken(context, consumer);
        } else if (HuaweiPushTokenManager.INSTANCE.getPushProvider().equals(mPushProvider)) {
            HuaweiPushTokenManager.INSTANCE.deleteToken(context, consumer);
        } else {
            consumer.accept(ErrorCode.LIBRARY_ERROR);
        }
    }

    public void deleteInstallation(@NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteInstallation: consumer=" + consumer);
        }

        if (FirebasePushTokenManager.INSTANCE.getPushProvider().equals(mPushProvider)) {
            FirebasePushTokenManager.INSTANCE.deleteInstallation(consumer);
        } else if (HuaweiPushTokenManager.INSTANCE.getPushProvider().equals(mPushProvider)) {
            HuaweiPushTokenManager.INSTANCE.deleteInstallation(consumer);
        } else {
            consumer.accept(ErrorCode.LIBRARY_ERROR);
        }
    }

    @NonNull
    public String getPushProvider() {
        return mPushProvider;
    }
}
