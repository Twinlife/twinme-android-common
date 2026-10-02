/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.common.GoogleApiAvailability;

public class BillingProviderFactory {
    @NonNull
    public static BillingProvider getInstance(@NonNull Activity activity, @NonNull BillingProviderListener billingProviderListener) {
        if (isGooglePlayServicesAvailable(activity)) {
            // Prioritize Play Services, if available
            return new PlayServicesBillingProvider(activity, billingProviderListener);
        }

        // Default to Huawei's AppGallery.
        // If it's not available either, it will call billingProviderListener.onBillingSetupFinished(ErrorCode.SERVICE_UNAVAILABLE)
        return new AppGalleryBillingProvider(activity, billingProviderListener);
    }

    private static boolean isGooglePlayServicesAvailable(@NonNull Context context) {
        int result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        return result == com.google.android.gms.common.ConnectionResult.SUCCESS;
    }
}
