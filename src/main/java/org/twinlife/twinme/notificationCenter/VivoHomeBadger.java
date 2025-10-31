/*
 *
 * Badge management based on: https://github.com/leolin310148/ShortcutBadger
 *
 */

package org.twinlife.twinme.notificationCenter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * @author leolin
 */
class VivoHomeBadger extends Badger {

    VivoHomeBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        Intent intent = new Intent("launcher.action.CHANGE_APPLICATION_NOTIFICATION_NUM");
        intent.putExtra("packageName", mContext.getPackageName());
        intent.putExtra("className", mComponentName.getClassName());
        intent.putExtra("notificationNum", badgeNumber);
        mContext.sendBroadcast(intent);
    }
}
