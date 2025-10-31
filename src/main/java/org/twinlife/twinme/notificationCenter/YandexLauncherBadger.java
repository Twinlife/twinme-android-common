/*
 *
 * Badge management based on: https://github.com/leolin310148/ShortcutBadger
 *
 */

package org.twinlife.twinme.notificationCenter;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * @author Nikolay Pakhomov
 * created 16/04/2018
 */
class YandexLauncherBadger extends Badger {

    private static final String AUTHORITY = "com.yandex.launcher.badges_external";
    private static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD_TO_CALL = "setBadgeNumber";

    private static final String COLUMN_CLASS = "class";
    private static final String COLUMN_PACKAGE = "package";
    private static final String COLUMN_BADGES_COUNT = "badges_count";

    YandexLauncherBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        Bundle extras = new Bundle();
        extras.putString(COLUMN_CLASS, mComponentName.getClassName());
        extras.putString(COLUMN_PACKAGE, mComponentName.getPackageName());
        extras.putString(COLUMN_BADGES_COUNT, String.valueOf(badgeNumber));
        mContext.getContentResolver().call(CONTENT_URI, METHOD_TO_CALL, null, extras);
    }
}
