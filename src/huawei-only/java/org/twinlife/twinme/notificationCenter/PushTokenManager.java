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

public class PushTokenManager {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PushTokenManager";

    @NonNull
    public static final PushTokenManager INSTANCE = new PushTokenManager();

    private PushTokenManager() {
    }

    public void createToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<String> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createToken: context=" + context + " consumer=" + consumer);
        }

        HuaweiPushTokenManager.INSTANCE.createToken(context, consumer);
    }

    public void deleteToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteToken: context=" + context + " consumer=" + consumer);
        }

        HuaweiPushTokenManager.INSTANCE.deleteToken(context, consumer);
    }

    public void deleteInstallation(@NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteInstallation: consumer=" + consumer);
        }

        HuaweiPushTokenManager.INSTANCE.deleteInstallation(consumer);
    }


    @NonNull
    public String getPushProvider() {
        return HuaweiPushTokenManager.INSTANCE.getPushProvider();
    }
}
