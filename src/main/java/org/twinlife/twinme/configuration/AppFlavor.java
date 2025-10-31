/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.configuration;

import org.twinlife.twinme.android.BuildConfig;

public final class AppFlavor {

    public static final boolean TWINME = BuildConfig.IS_TWINME && !BuildConfig.IS_PLUS;
    public static final boolean TWINME_PLUS = BuildConfig.IS_TWINME && BuildConfig.IS_PLUS;
    public static final boolean SKRED = BuildConfig.IS_SKRED;

    private AppFlavor(){
        throw new AssertionError("Not instantiable");
    }
}
