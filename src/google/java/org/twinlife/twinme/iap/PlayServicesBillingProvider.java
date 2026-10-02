/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.iap;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import org.twinlife.twinlife.AccountService;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.ErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayServicesBillingProvider implements BillingProvider, PurchasesUpdatedListener, BillingClientStateListener, ProductDetailsResponseListener {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PlayServicesBillingProvider";
    @NonNull
    private final BillingClient mBillingClient;
    @NonNull
    private final Activity mActivity;
    @NonNull
    private final Map<ProductDetails, com.android.billingclient.api.ProductDetails> mProducts = new HashMap<>();
    @NonNull
    private final BillingProviderListener mBillingProviderListener;

    public PlayServicesBillingProvider(@NonNull Activity activity, @NonNull BillingProviderListener billingProviderListener) {
        mActivity = activity;
        mBillingProviderListener = billingProviderListener;
        mBillingClient = BillingClient.newBuilder(activity.getApplicationContext())
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enablePrepaidPlans().enableOneTimeProducts().build())
                .build();

        mBillingClient.startConnection(this);
    }

    @Override
    public void fetchPurchases(@NonNull Consumer<List<Purchase>> onComplete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "fetchPurchases");
        }

        if (mProducts.isEmpty()) {
            return;
        }

        PurchasesResponseListener purchasesResponseListener = (billingResult, purchases) ->
                onComplete.onGet(mapResult(billingResult), mapPurchases(purchases));

        mBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(), purchasesResponseListener);
    }

    @Override
    public void fetchSubscriptions() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSubscriptions");
        }

        List<String> productIdList = Arrays.asList(ONE_MONTH_SUBSCRIPTION_ID, SIX_MONTHS_SUBSCRIPTION_ID, ONE_YEAR_SUBSCRIPTION_ID);
        ArrayList<QueryProductDetailsParams.Product> productList = new ArrayList<>();

        for (String productId : productIdList) {
            QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build();
            productList.add(product);
        }

        QueryProductDetailsParams queryProductDetailsParams = QueryProductDetailsParams.newBuilder().setProductList(productList).build();

        mBillingClient.queryProductDetailsAsync(queryProductDetailsParams, this);
    }

    @Override
    public void subscribeToProduct(@NonNull String productId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "subscribeToProduct: productId=" + productId);
        }

        for (com.android.billingclient.api.ProductDetails productDetails : mProducts.values()) {
            if (productDetails.getProductId().equals(productId)) {
                subscribe(productDetails);
                break;
            }
        }
    }

    @Override
    public void manageSubscription(@NonNull String productId, @Nullable String packageName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "manageSubscription: productId=" + productId + " packageName=" + packageName);
        }

        if (packageName == null) {
            packageName = mActivity.getPackageName();
        }

        String url = "https://play.google.com/store/account/subscriptions?sku=" + productId
                + "&package=" + packageName;
        mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mBillingClient.isReady()) {
            mBillingClient.endConnection();
        }
    }

    @Override
    public AccountService.MerchantIdentification getMerchantIdentification() {
        return AccountService.MerchantIdentification.MERCHANT_GOOGLE;
    }

    @Override
    public void onBillingServiceDisconnected() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBillingServiceDisconnected");
        }

        // Try to restart the connection on the next request to
        // Google Play by calling the startConnection() method.
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBillingSetupFinished: " + billingResult);
        }

        mBillingProviderListener.onBillingSetupFinished(mapResult(billingResult));
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<com.android.billingclient.api.Purchase> purchases) {
        mBillingProviderListener.onPurchasesUpdated(mapResult(billingResult), mapPurchases(purchases));
    }

    @Override
    public void onProductDetailsResponse(@NonNull BillingResult billingResult, @NonNull QueryProductDetailsResult queryProductDetailsResult) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onProductDetailsResponse: billingResult=" + billingResult + " queryProductDetailsResult=" + queryProductDetailsResult);
        }

        setProductDetails(queryProductDetailsResult);

        mBillingProviderListener.onProductDetailsResponse(mapResult(billingResult), new ArrayList<>(mProducts.keySet()));
    }

    private void subscribe(com.android.billingclient.api.ProductDetails productDetails) {
        if (DEBUG) {
            Log.d(LOG_TAG, "subscribe: " + productDetails);
        }

        if (productDetails.getSubscriptionOfferDetails() != null) {

            String offerToken = null;
            for (com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails subscriptionOfferDetails : productDetails.getSubscriptionOfferDetails()) {
                if (subscriptionOfferDetails.getPricingPhases().getPricingPhaseList().size() == 2) {
                    // 2 phases => free trial then regular subscription, this is the one we want.
                    offerToken = subscriptionOfferDetails.getOfferToken();
                    break;
                }
            }

            if (offerToken == null) {
                // No free trial offer found, but we should have at least the regular subscription.
                offerToken = productDetails.getSubscriptionOfferDetails().get(0).getOfferToken();
            }

            List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = new ArrayList<>();

            BillingFlowParams.ProductDetailsParams productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build();
            productDetailsParamsList.add(productDetailsParams);

            BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build();

            mBillingClient.launchBillingFlow(mActivity, billingFlowParams);
        }
    }

    private static final Map<Integer, ErrorCode> BILLING_RESPONSE_TO_ERROR = Map.of(
            BillingClient.BillingResponseCode.OK, ErrorCode.SUCCESS,
            BillingClient.BillingResponseCode.USER_CANCELED, ErrorCode.CANCELED_OPERATION,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
            // BILLING_UNAVAILABLE: A user billing error occurred during processing (CC declined, ...)
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, ErrorCode.NOT_AUTHORIZED_OPERATION,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, ErrorCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE, ErrorCode.ITEM_NOT_FOUND,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR, ErrorCode.LIBRARY_ERROR,
            BillingClient.BillingResponseCode.ERROR, ErrorCode.LIBRARY_ERROR,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED, ErrorCode.EXISTS,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED, ErrorCode.NO_PERMISSION
    );

    @NonNull
    private ErrorCode mapResult(@NonNull BillingResult billingResult) {
        ErrorCode errorCode = BILLING_RESPONSE_TO_ERROR.get(billingResult.getResponseCode());

        if (errorCode != null) {
            return errorCode;
        }

        return ErrorCode.LIBRARY_ERROR;
    }

    private void setProductDetails(@Nullable QueryProductDetailsResult queryProductDetailsResult) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setProductDetails: queryProductDetailsResult=" + queryProductDetailsResult);
        }

        mProducts.clear();

        if (queryProductDetailsResult == null) {
            return;
        }

        for (com.android.billingclient.api.ProductDetails psProductDetails : queryProductDetailsResult.getProductDetailsList()) {
            List<ProductDetails.SubscriptionOfferDetails> subscriptionOfferDetails = mapSubscriptionOfferDetails(psProductDetails.getSubscriptionOfferDetails());
            mProducts.put(new ProductDetails(psProductDetails.getProductId(), subscriptionOfferDetails), psProductDetails);
        }
    }

    @NonNull
    private List<ProductDetails.SubscriptionOfferDetails> mapSubscriptionOfferDetails(@Nullable List<com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails> playStoreSubscriptionOfferDetails) {
        if (playStoreSubscriptionOfferDetails == null) {
            return Collections.emptyList();
        }

        List<ProductDetails.SubscriptionOfferDetails> subscriptionOfferDetails = new ArrayList<>();

        for (com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails psSubscriptionOfferDetails : playStoreSubscriptionOfferDetails) {
            List<ProductDetails.PricingPhase> pricingPhases = new ArrayList<>();
            for (com.android.billingclient.api.ProductDetails.PricingPhase psPricingPhase : psSubscriptionOfferDetails.getPricingPhases().getPricingPhaseList()) {
                pricingPhases.add(new ProductDetails.PricingPhase(psPricingPhase.getFormattedPrice(), psPricingPhase.getPriceAmountMicros()));
            }
            subscriptionOfferDetails.add(new ProductDetails.SubscriptionOfferDetails(pricingPhases));
        }

        return subscriptionOfferDetails;
    }

    @Nullable
    private List<Purchase> mapPurchases(@Nullable List<com.android.billingclient.api.Purchase> playStorePurchases) {

        if (playStorePurchases == null) {
            return null;
        }

        List<Purchase> purchases = new ArrayList<>();

        for (com.android.billingclient.api.Purchase psPurchase : playStorePurchases) {
            purchases.add(new Purchase(psPurchase.getPurchaseState(), psPurchase.getOrderId(), psPurchase.getPurchaseToken(), psPurchase.getProducts(), psPurchase.getPurchaseTime()));
        }

        return purchases;
    }
}
