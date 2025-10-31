/*
 *  Copyright (c) 2017-2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 */

package org.twinlife.twinme.notificationCenter;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

class HuaweiBadger extends Badger {
    private static final String LOG_TAG = "HuaweiBadger";
    private static final boolean DEBUG = false;

    HuaweiBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateBadgeNumber badgeNumber=" + badgeNumber);
        }

        Bundle bundle = new Bundle();
        bundle.putString("package", mComponentName.getPackageName());
        bundle.putString("class", mComponentName.getClassName());
        bundle.putInt("badgenumber", badgeNumber);
        mContext.getContentResolver().call(Uri.parse("content://com.huawei.android.launcher.settings/badge/"), "change_badge", null, bundle);
    }
}
