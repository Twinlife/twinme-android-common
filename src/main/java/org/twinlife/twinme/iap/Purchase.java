/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class Purchase {

    public interface PurchaseState {
        int UNSPECIFIED_STATE = 0;
        int PURCHASED = 1;
        int PENDING = 2;
    }

    @NonNull
    private final List<String> mProducts;
    private final int mPurchaseState;
    @Nullable
    private final String mOrderId;
    @NonNull
    private final String mPurchaseToken;
    private final long mPurchaseTime;

    public Purchase(int purchaseState, @Nullable String orderId, @NonNull String purchaseToken, @NonNull List<String> products, long purchaseTime) {
        mPurchaseState = purchaseState;
        mOrderId = orderId;
        mPurchaseToken = purchaseToken;
        mProducts = new ArrayList<>(products);
        mPurchaseTime = purchaseTime;
    }

    @NonNull
    public synchronized List<String> getProducts() {
        return mProducts;
    }

    public synchronized int getPurchaseState() {
        return mPurchaseState;
    }

    @Nullable
    public String getOrderId() {
        return mOrderId;
    }

    @NonNull
    public String getPurchaseToken() {
        return mPurchaseToken;
    }

    public long getPurchaseTime() {
        return mPurchaseTime;
    }

    @NonNull
    @Override
    public String toString() {
        return "Purchase{" +
                "mProducts=" + mProducts +
                ", mPurchaseState=" + mPurchaseState +
                ", mOrderId='" + mOrderId + '\'' +
                ", mPurchaseToken='" + mPurchaseToken + '\'' +
                ", mPurchaseTime=" + mPurchaseTime +
                '}';
    }
}
