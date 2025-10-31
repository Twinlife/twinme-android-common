/*
 *  Copyright (c) 2017-2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.notificationCenter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

class DefaultBadger extends Badger {
    private static final String LOG_TAG = "DefaultBadger";
    private static final boolean DEBUG = false;

    private static final String INTENT_ACTION = "android.intent.action.BADGE_COUNT_UPDATE";
    private static final String INTENT_EXTRA_BADGE_COUNT = "badge_count";
    private static final String INTENT_EXTRA_PACKAGENAME = "badge_count_package_name";
    private static final String INTENT_EXTRA_ACTIVITY_NAME = "badge_count_class_name";

    DefaultBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateBadgeNumber badgeNumber=" + badgeNumber);
        }

        Intent intent = new Intent(INTENT_ACTION);
        intent.putExtra(INTENT_EXTRA_BADGE_COUNT, badgeNumber);
        intent.putExtra(INTENT_EXTRA_PACKAGENAME, mComponentName.getPackageName());
        intent.putExtra(INTENT_EXTRA_ACTIVITY_NAME, mComponentName.getClassName());
        sendBroadcast(intent);
    }
}
