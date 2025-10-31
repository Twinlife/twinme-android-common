/*
 *  Copyright (c) 2016 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributor: Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 */

package org.twinlife.twinme.utils;

import android.view.View;

public abstract class OnGuardedClickListener implements android.view.View.OnClickListener {

    private final static long THRESHOLD = 500;

    private long mTimestamp = 0;

    public abstract void onGuardedClick(View view);

    @Override
    public void onClick(View view) {

        long lastTimestamp = mTimestamp;
        mTimestamp = System.currentTimeMillis();
        if (mTimestamp - lastTimestamp > THRESHOLD) {
            onGuardedClick(view);
        }
    }
}
