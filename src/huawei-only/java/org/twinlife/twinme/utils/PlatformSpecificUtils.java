/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.content.Context;

import androidx.annotation.NonNull;

public class PlatformSpecificUtils {

    private PlatformSpecificUtils(){
        throw new AssertionError("Not instantiable");
    }

    public static boolean isGooglePlayServicesAvailable(@SuppressWarnings("unused") @NonNull Context context) {
        return false;
    }
}
