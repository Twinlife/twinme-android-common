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
 * @author Leo Lin
 */
class NewHtcHomeBadger extends Badger {

    public static final String INTENT_UPDATE_SHORTCUT = "com.htc.launcher.action.UPDATE_SHORTCUT";
    public static final String INTENT_SET_NOTIFICATION = "com.htc.launcher.action.SET_NOTIFICATION";
    public static final String PACKAGENAME = "packagename";
    public static final String COUNT = "count";
    public static final String EXTRA_COMPONENT = "com.htc.launcher.extra.COMPONENT";
    public static final String EXTRA_COUNT = "com.htc.launcher.extra.COUNT";

    NewHtcHomeBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {

        Intent intent1 = new Intent(INTENT_SET_NOTIFICATION);

        intent1.putExtra(EXTRA_COMPONENT, mComponentName.flattenToShortString());
        intent1.putExtra(EXTRA_COUNT, badgeNumber);

        Intent intent = new Intent(INTENT_UPDATE_SHORTCUT);

        intent.putExtra(PACKAGENAME, mComponentName.getPackageName());
        intent.putExtra(COUNT, badgeNumber);

        try {
            sendIntentExplicitly(mContext, intent1);
        } catch (Exception ignored) {
        }

        try {
            sendIntentExplicitly(mContext, intent);
        } catch (Exception ignored) {
        }
    }
}
