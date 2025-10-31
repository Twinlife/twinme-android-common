/*
 *  Copyright (c) 2022-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.percentlayout.widget.PercentRelativeLayout;

/**
 * Load the link metadata from the loader manager thread.
 */
public class LayoutUtil {
    private static final String LOG_TAG = "LinkLoader";
    private static final boolean DEBUG = false;

    public static void matchParentLayout(@NonNull View view) {
        if (DEBUG) {
            Log.d(LOG_TAG, "matchParentLayout " + view);
        }

        PercentRelativeLayout.LayoutParams layoutParams = new PercentRelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        view.setLayoutParams(layoutParams);
    }
}