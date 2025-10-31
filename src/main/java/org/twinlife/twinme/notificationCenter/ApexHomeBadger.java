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
 * @author Gernot Pansy
 */
class ApexHomeBadger extends Badger {

    private static final String INTENT_UPDATE_COUNTER = "com.anddoes.launcher.COUNTER_CHANGED";
    private static final String PACKAGENAME = "package";
    private static final String COUNT = "count";
    private static final String CLASS = "class";

    ApexHomeBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        Intent intent = new Intent(INTENT_UPDATE_COUNTER);
        intent.putExtra(PACKAGENAME, mComponentName.getPackageName());
        intent.putExtra(COUNT, badgeNumber);
        intent.putExtra(CLASS, mComponentName.getClassName());

        sendIntentExplicitly(mContext, intent);
    }
}
