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

class ZTEHomeBadger extends Badger {

    ZTEHomeBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        Bundle extra = new Bundle();
        extra.putInt("app_badge_count", badgeNumber);
        extra.putString("app_badge_component_name", mComponentName.flattenToString());

        mContext.getContentResolver().call(Uri.parse("content://com.android.launcher3.cornermark.unreadbadge"),
                "setAppUnreadCount", null, extra);
    }
} 

