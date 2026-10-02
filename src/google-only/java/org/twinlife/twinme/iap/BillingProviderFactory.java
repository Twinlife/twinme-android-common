/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import android.app.Activity;

import androidx.annotation.NonNull;

public class BillingProviderFactory {
    @NonNull
    public static BillingProvider getInstance(@NonNull Activity activity, @NonNull BillingProviderListener billingProviderListener) {
        return new PlayServicesBillingProvider(activity, billingProviderListener);
    }
}
