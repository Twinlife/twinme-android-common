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

class OPPOHomeBader extends Badger {

    private static final String PROVIDER_CONTENT_URI = "content://com.android.badge/badge";
    private static final String INTENT_EXTRA_BADGEUPGRADE_COUNT = "app_badge_count";
    private int mCurrentTotalCount = -1;

    OPPOHomeBader(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        if (mCurrentTotalCount == badgeNumber) {
            return;
        }

        mCurrentTotalCount = badgeNumber;
        Bundle extras = new Bundle();
        extras.putInt(INTENT_EXTRA_BADGEUPGRADE_COUNT, badgeNumber);
        mContext.getContentResolver().call(Uri.parse(PROVIDER_CONTENT_URI), "setAppBadgeCount", null, extras);
    }
}