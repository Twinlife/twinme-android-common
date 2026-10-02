/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import androidx.annotation.NonNull;import androidx.annotation.Nullable;

import org.twinlife.twinlife.ErrorCode;

import java.util.List;

public interface BillingProviderListener {
    void onPurchasesUpdated(@NonNull ErrorCode errorCode, @Nullable List<Purchase> purchases);

    void onBillingSetupFinished(@NonNull ErrorCode result);

    void onProductDetailsResponse(@NonNull ErrorCode result, @Nullable List<ProductDetails> productDetails);
}
