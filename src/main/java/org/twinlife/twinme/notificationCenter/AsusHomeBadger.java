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
class AsusHomeBadger extends Badger {

    // It seems that Asus handle Sony like badges better than old implementation...
    private static final String SONY_INTENT_ACTION = "com.sonyericsson.home.action.UPDATE_BADGE";
    private static final String SONY_INTENT_EXTRA_PACKAGE_NAME = "com.sonyericsson.home.intent.extra.badge.PACKAGE_NAME";
    private static final String SONY_INTENT_EXTRA_ACTIVITY_NAME = "com.sonyericsson.home.intent.extra.badge.ACTIVITY_NAME";
    private static final String SONY_INTENT_EXTRA_MESSAGE = "com.sonyericsson.home.intent.extra.badge.MESSAGE";
    private static final String SONY_INTENT_EXTRA_SHOW_MESSAGE = "com.sonyericsson.home.intent.extra.badge.SHOW_MESSAGE";

    AsusHomeBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        Intent intent = new Intent(SONY_INTENT_ACTION);
        intent.putExtra(SONY_INTENT_EXTRA_PACKAGE_NAME, mComponentName.getPackageName());
        intent.putExtra(SONY_INTENT_EXTRA_ACTIVITY_NAME, mComponentName.getClassName());
        intent.putExtra(SONY_INTENT_EXTRA_MESSAGE, String.valueOf(badgeNumber));
        intent.putExtra(SONY_INTENT_EXTRA_SHOW_MESSAGE, badgeNumber > 0);
        mContext.sendBroadcast(intent);
    }
}
