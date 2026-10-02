/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ProductDetails {

    public static class SubscriptionOfferDetails {
        @NonNull
        private final List<PricingPhase> mPricingPhases;

        public SubscriptionOfferDetails(@NonNull List<PricingPhase> pricingPhases) {
            mPricingPhases = new ArrayList<>(pricingPhases);
        }

        public List<PricingPhase> getPricingPhases() {
            return mPricingPhases;
        }

        @NonNull
        @Override
        public String toString() {
            return "SubscriptionOfferDetails{" +
                    "mPricingPhases=" + mPricingPhases +
                    '}';
        }
    }

    public static class PricingPhase {
        @NonNull
        private final String mFormattedPrice;
        private final long mPriceAmountMicros;

        public PricingPhase(@NonNull String formattedPrice, long priceAmountMicros) {
            mFormattedPrice = formattedPrice;
            mPriceAmountMicros = priceAmountMicros;
        }

        @NonNull
        public String getFormattedPrice() {
            return mFormattedPrice;
        }

        public long getPriceAmountMicros() {
            return mPriceAmountMicros;
        }

        @NonNull
        @Override
        public String toString() {
            return "PricingPhase{" +
                    "mFormattedPrice='" + mFormattedPrice + '\'' +
                    ", mPriceAmountMicros=" + mPriceAmountMicros +
                    '}';
        }
    }

    @NonNull
    private final String mProductId;
    @NonNull
    private final List<SubscriptionOfferDetails> mSubscriptionOfferDetails;

    ProductDetails(@NonNull String productId, @NonNull List<SubscriptionOfferDetails> subscriptionOfferDetails) {
        mProductId = productId;
        mSubscriptionOfferDetails = new ArrayList<>(subscriptionOfferDetails);
    }

    public String getProductId() {
        return mProductId;
    }

    public List<SubscriptionOfferDetails> getSubscriptionOfferDetails() {
        return mSubscriptionOfferDetails;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ProductDetails that = (ProductDetails) o;
        return Objects.equals(mProductId, that.mProductId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mProductId);
    }

    @NonNull
    @Override
    public String toString() {
        return "ProductDetails{" +
                "mProductId='" + mProductId + '\'' +
                ", mSubscriptionOfferDetails=" + mSubscriptionOfferDetails +
                '}';
    }
}
