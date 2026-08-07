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

import com.huawei.hms.aaid.HmsInstanceId;
import com.huawei.hms.common.ApiException;

import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinlife.ManagementService;
import org.twinlife.twinme.TwinmeContext;

public class HuaweiPushTokenManager {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "HuaweiPushTokenManager";

    private static final String TOKEN_SCOPE = "HCM";

    @NonNull
    public static final HuaweiPushTokenManager INSTANCE = new HuaweiPushTokenManager();

    private HuaweiPushTokenManager() {
    }

    public void createToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<String> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createToken: context=" + context + " consumer=" + consumer);
        }

        try {
            String pushToken = HmsInstanceId.getInstance(context).getToken(context.getPackageName(), TOKEN_SCOPE);
            consumer.accept(pushToken);
        } catch (ApiException e) {
            Log.e(LOG_TAG, "HMS error while getting token", e);
            consumer.accept(null);
        }
    }

    public void deleteToken(@NonNull Context context, @NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteToken: context=" + context + " consumer=" + consumer);
        }

        try {
            HmsInstanceId.getInstance(context).deleteToken(context.getPackageName(), TOKEN_SCOPE);
            consumer.accept(ErrorCode.SUCCESS);
        } catch (ApiException e) {
            Log.e(LOG_TAG, "HMS error while deleting token", e);
            consumer.accept(null);
        }
    }

    public void deleteInstallation(@NonNull TwinmeContext.Consumer<ErrorCode> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteInstallation: consumer=" + consumer);
        }
        //NOOP
    }

    @NonNull
    public String getPushProvider() {
        return ManagementService.PUSH_NOTIFICATION_HUAWEI_VARIANT;
    }
}
