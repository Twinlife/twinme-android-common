/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AccountService;
import org.twinlife.twinlife.Consumer;

import java.util.List;

public interface BillingProvider {
    String ONE_MONTH_SUBSCRIPTION_ID = "skred.subscription.one_month_auto_renew";
    String SIX_MONTHS_SUBSCRIPTION_ID = "skred.subscription.six_month_auto_renew";
    String ONE_YEAR_SUBSCRIPTION_ID = "skred.subscription.1_year_auto_renew";

    void fetchPurchases(@NonNull Consumer<List<Purchase>> onComplete);

    void fetchSubscriptions();

    void subscribeToProduct(@NonNull String productId);

    void manageSubscription(@NonNull String productId, @Nullable String packageName);

    void dispose();

    AccountService.MerchantIdentification getMerchantIdentification();

    default void handleActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        // noop by default. Implemented in AppGalleryBillingProvider to handle Huawei's billing SDK sign-in and purchase results.
    }
}
