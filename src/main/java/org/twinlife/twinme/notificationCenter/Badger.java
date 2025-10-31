/*
 *  Copyright (c) 2017-2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

/*
 *
 * Badge management based on: https://github.com/leolin310148/ShortcutBadger
 *
 */

package org.twinlife.twinme.notificationCenter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

abstract class Badger {
    private static final String LOG_TAG = "Badger";
    private static final boolean DEBUG = false;

    private static final String INTENT_ACTION = "android.intent.action.BADGE_COUNT_UPDATE";

    static Badger getBadger(Context context, ComponentName componentName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getBadger: context=" + context + " componentName=" + componentName);
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null) {

            String packageName = resolveInfo.activityInfo.packageName;

            if ("com.huawei.android.launcher".equals(packageName)) {
                return new HuaweiBadger(context, componentName);
            }

            if ("com.sec.android.app.launcher".equals(packageName) || "com.sec.android.app.twlauncher".equals(packageName)) {
                if (canResolveBroadcast(context, new Intent(INTENT_ACTION))) {
                    return new DefaultBadger(context, componentName);
                } else {
                    return new SamsungBadger(context, componentName);
                }
            }

            if ("com.vivo.launcher".equals(packageName)) {
                return new VivoHomeBadger(context, componentName);
            }

            if ("com.oppo.launcher".equals(packageName) || "com.zui.launcher".equals(packageName)) {
                return new OPPOHomeBader(context, componentName);
            }

            if ("com.yandex.launcher".equals(packageName)) {
                return new YandexLauncherBadger(context, componentName);
            }

            if ("com.htc.launcher".equals(packageName)) {
                return new NewHtcHomeBadger(context, componentName);
            }

            if ("com.sonyericsson.home".equals(packageName) || "com.sonymobile.home".equals(packageName)) {
                return new SonyHomeBadger(context, componentName);
            }

            if ("com.asus.launcher".equals(packageName)) {
                return new AsusHomeBadger(context, componentName);
            }

            if ("com.anddoes.launcher".equals(packageName)) {
                return new ApexHomeBadger(context, componentName);
            }

            if (Build.MANUFACTURER.equalsIgnoreCase("ZTE")) {
                return new ZTEHomeBadger(context, componentName);
            }

            if (Build.MANUFACTURER.equalsIgnoreCase("OPPO")) {
                return new OPPOHomeBader(context, componentName);
            }

            if (Build.MANUFACTURER.equalsIgnoreCase("VIVO")) {
                return new VivoHomeBadger(context, componentName);
            }
        }

        return new DefaultBadger(context, componentName);
    }

    protected final Context mContext;
    protected final ComponentName mComponentName;

    protected Badger(Context context, ComponentName componentName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "Badger context=" + context + " componentName=" + componentName);
        }

        mContext = context;
        mComponentName = componentName;
    }

    final void setBadgeNumber(int badgeNumber) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setBadgeNumber badgeNumber=" + badgeNumber);
        }

        try {
            updateBadgeNumber(badgeNumber);

        } catch (Exception exception) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Exception", exception);
            }
        }
    }

    protected abstract void updateBadgeNumber(int badgeNumber);

    protected static List<ResolveInfo> resolveBroadcast(Context context, Intent intent) {
        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> receivers = packageManager.queryBroadcastReceivers(intent, 0);

        return receivers != null ? receivers : Collections.emptyList();
    }

    static boolean canResolveBroadcast(Context context, Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "canResolveBroadcast context=" + context + " intent=" + intent);
        }

        return resolveBroadcast(context, intent).size() > 0;
    }

    protected void sendBroadcast(@NonNull Intent intent) {

        if (canResolveBroadcast(mContext, intent)) {
            mContext.sendBroadcast(intent);
        }
    }

    protected static void sendIntentExplicitly(Context context, Intent intent) {
        List<ResolveInfo> resolveInfos = resolveBroadcast(context, intent);

        if (resolveInfos.size() == 0) {
            return;
        }

        for (ResolveInfo info : resolveInfos) {
            Intent actualIntent = new Intent(intent);

            if (info != null) {
                actualIntent.setPackage(info.resolvePackageName);
                context.sendBroadcast(actualIntent);
            }
        }
    }
}
