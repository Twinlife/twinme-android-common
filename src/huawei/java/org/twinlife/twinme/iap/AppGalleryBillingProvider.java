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
import android.content.IntentSender;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.huawei.hmf.tasks.Task;
import com.huawei.hms.iap.Iap;
import com.huawei.hms.iap.IapApiException;
import com.huawei.hms.iap.IapClient;
import com.huawei.hms.iap.entity.InAppPurchaseData;
import com.huawei.hms.iap.entity.IsEnvReadyResult;
import com.huawei.hms.iap.entity.OrderStatusCode;
import com.huawei.hms.iap.entity.OwnedPurchasesReq;
import com.huawei.hms.iap.entity.OwnedPurchasesResult;
import com.huawei.hms.iap.entity.ProductInfo;
import com.huawei.hms.iap.entity.ProductInfoReq;
import com.huawei.hms.iap.entity.ProductInfoResult;
import com.huawei.hms.iap.entity.PurchaseIntentReq;
import com.huawei.hms.iap.entity.PurchaseIntentResult;
import com.huawei.hms.iap.entity.PurchaseResultInfo;
import com.huawei.hms.iap.entity.StartIapActivityReq;
import com.huawei.hms.iap.entity.StartIapActivityResult;
import com.huawei.hms.iap.util.IapClientHelper;
import com.huawei.hms.support.api.client.Status;

import org.json.JSONException;
import org.twinlife.twinlife.AccountService;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.ErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppGalleryBillingProvider implements BillingProvider {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "AppGalleryBillingProvider";

    private static final int SIGN_IN_REQUEST_CODE = 6666;
    private static final int SUBSCRIBE_REQUEST_CODE = 6667;

    @NonNull
    private final Activity mActivity;
    @NonNull
    private final BillingProviderListener mBillingProviderListener;
    @NonNull
    private final IapClient mIapClient;
    @NonNull
    private final Map<ProductDetails, ProductInfo> mProducts = new HashMap<>();

    public AppGalleryBillingProvider(@NonNull Activity activity, @NonNull BillingProviderListener billingProviderListener) {
        mActivity = activity;
        mBillingProviderListener = billingProviderListener;

        mIapClient = Iap.getIapClient(mActivity);
        Task<IsEnvReadyResult> iapClientTask = mIapClient.isEnvReady();
        iapClientTask.addOnSuccessListener(result -> mBillingProviderListener.onBillingSetupFinished(ErrorCode.SUCCESS))
                .addOnFailureListener(exception -> {
                    if (exception instanceof IapApiException) {
                        IapApiException apiException = (IapApiException) exception;
                        Status status = apiException.getStatus();
                        if (status.getStatusCode() == OrderStatusCode.ORDER_HWID_NOT_LOGIN) {
                            // HUAWEI ID is not signed in.
                            if (status.hasResolution()) {
                                try {
                                    status.startResolutionForResult(mActivity, SIGN_IN_REQUEST_CODE);
                                } catch (IntentSender.SendIntentException e) {
                                    Log.e(LOG_TAG, "Could not start resolution", e);
                                    mBillingProviderListener.onBillingSetupFinished(ErrorCode.SERVICE_UNAVAILABLE);
                                }
                            }
                        } else if (status.getStatusCode() == OrderStatusCode.ORDER_ACCOUNT_AREA_NOT_SUPPORTED) {
                            Log.e(LOG_TAG, "Area not supported");
                            mBillingProviderListener.onBillingSetupFinished(ErrorCode.SERVICE_UNAVAILABLE);
                        }
                    } else {
                        Log.e(LOG_TAG, "Could not initialize IAP client", exception);
                        mBillingProviderListener.onBillingSetupFinished(ErrorCode.SERVICE_UNAVAILABLE);
                    }
                });
    }

    @Override
    public void fetchPurchases(@NonNull Consumer<List<Purchase>> onComplete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "fetchPurchases");
        }

        OwnedPurchasesReq req = new OwnedPurchasesReq();
        // 2 = subscription
        req.setPriceType(2);

        Task<OwnedPurchasesResult> task = mIapClient.obtainOwnedPurchases(req);
        task.addOnSuccessListener(result -> {
            if (result == null || result.getInAppPurchaseDataList() == null) {
                onComplete.onGet(ErrorCode.LIBRARY_ERROR, null);
                return;
            }

            List<Purchase> purchases = new ArrayList<>();

            for (String inAppPurchaseData : result.getInAppPurchaseDataList()) {
                Purchase purchase = mapPurchase(inAppPurchaseData);
                if (purchase == null) {
                    onComplete.onGet(ErrorCode.LIBRARY_ERROR, null);
                    return;
                }
                purchases.add(purchase);
            }

            onComplete.onGet(ErrorCode.SUCCESS, purchases);
        }).addOnFailureListener(e -> {
            Log.e(LOG_TAG, "Couldn't fetch purchases", e);
            onComplete.onGet(ErrorCode.LIBRARY_ERROR, null);
        });
    }

    @Override
    public void fetchSubscriptions() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSubscriptions");
        }

        List<String> productIdList = Arrays.asList(ONE_MONTH_SUBSCRIPTION_ID, SIX_MONTHS_SUBSCRIPTION_ID, ONE_YEAR_SUBSCRIPTION_ID);

        ProductInfoReq req = new ProductInfoReq();
        // priceType: 0: consumable; 1: non-consumable; 2: subscription
        req.setPriceType(2);
        req.setProductIds(productIdList);

        Task<ProductInfoResult> task = mIapClient.obtainProductInfo(req);
        task.addOnSuccessListener(result -> {
            List<ProductInfo> productList = result.getProductInfoList();
            setProductDetails(productList);
            mBillingProviderListener.onProductDetailsResponse(ErrorCode.SUCCESS, new ArrayList<>(mProducts.keySet()));
        }).addOnFailureListener(e -> {
            Log.e(LOG_TAG, "Couldn't fetch subscriptions", e);
            mBillingProviderListener.onProductDetailsResponse(ErrorCode.SERVICE_UNAVAILABLE, new ArrayList<>(mProducts.keySet()));
        });
    }

    @Override
    public void subscribeToProduct(@NonNull String productId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "subscribeToProduct: productId=" + productId);
        }

        PurchaseIntentReq req = new PurchaseIntentReq();
        req.setProductId(productId);
        // priceType: 0: consumable; 1: non-consumable; 2: subscription
        req.setPriceType(2);

        Task<PurchaseIntentResult> task = mIapClient.createPurchaseIntent(req);
        task.addOnSuccessListener(result -> {
            Status status = result.getStatus();
            if (status.hasResolution()) {
                try {
                    status.startResolutionForResult(mActivity, SUBSCRIBE_REQUEST_CODE);
                } catch (IntentSender.SendIntentException e) {
                    Log.e(LOG_TAG, "Could not start subscription process", e);
                    mBillingProviderListener.onPurchasesUpdated(ErrorCode.LIBRARY_ERROR, null);
                }
            }
        }).addOnFailureListener(e -> {
            Log.e(LOG_TAG, "Could not start subscription process", e);
            mBillingProviderListener.onPurchasesUpdated(ErrorCode.LIBRARY_ERROR, null);
        });
    }

    @Override
    public void manageSubscription(@NonNull String productId, @Nullable String packageName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "manageSubscription: productId=" + productId + " packageName=" + packageName);
        }

        StartIapActivityReq req = new StartIapActivityReq();
        req.setType(StartIapActivityReq.TYPE_SUBSCRIBE_MANAGER_ACTIVITY);

        Task<StartIapActivityResult> task = mIapClient.startIapActivity(req);
        task.addOnSuccessListener(result -> {
            if (result != null) {
                result.startActivity(mActivity);
            }
        }).addOnFailureListener(e -> Log.e(LOG_TAG, "Couldn't open subscription mgmt", e));
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }
    }

    @Override
    public AccountService.MerchantIdentification getMerchantIdentification() {
        return AccountService.MerchantIdentification.MERCHANT_HUAWEI;
    }

    @Override
    public void handleActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (DEBUG) {
            Log.d(LOG_TAG, "handleActivityResult: requestCode=" + requestCode + " resultCode=" + resultCode + " data=" + data);
        }

        if (requestCode == SIGN_IN_REQUEST_CODE) {
            if (data != null) {
                int returnCode = IapClientHelper.parseRespCodeFromIntent(data);
                // Just guessing here as Huawei hasn't bothered documenting possible values
                // https://developer.huawei.com/consumer/en/doc/HMSCore-References/iapclienthelper-0000001050135910#section1411517214208
                if (returnCode == 0) {
                    mBillingProviderListener.onBillingSetupFinished(ErrorCode.SUCCESS);
                } else {
                    Log.e(LOG_TAG, "Could not initialize IAP client: error code = " + returnCode);
                    mBillingProviderListener.onBillingSetupFinished(ErrorCode.SERVICE_UNAVAILABLE);
                }
            }
        } else if (requestCode == SUBSCRIBE_REQUEST_CODE) {
            if (data == null) {
                Log.e(LOG_TAG, "can't process subscription result: data is null");
                mBillingProviderListener.onPurchasesUpdated(ErrorCode.LIBRARY_ERROR, null);
                return;
            }

            PurchaseResultInfo purchaseResultInfo = mIapClient.parsePurchaseResultInfoFromIntent(data);
            switch (purchaseResultInfo.getReturnCode()) {
                case OrderStatusCode.ORDER_STATE_SUCCESS:
                    Purchase purchase = mapPurchase(purchaseResultInfo.getInAppPurchaseData());
                    mBillingProviderListener.onPurchasesUpdated(ErrorCode.SUCCESS, Collections.singletonList(purchase));
                    break;
                case OrderStatusCode.ORDER_PRODUCT_OWNED:
                    // TODO IAP: purchaseResultInfo.getInAppPurchaseData() is null in this case. Call fetchSubscriptions() again?
                    // This should not happen, if we already have a subscription the server doesn't know about, MainService should have handled it on startup.
                    break;
                case OrderStatusCode.ORDER_STATE_CANCEL:
                    mBillingProviderListener.onPurchasesUpdated(ErrorCode.CANCELED_OPERATION, null);
                    break;
                default:
                    mBillingProviderListener.onPurchasesUpdated(ErrorCode.LIBRARY_ERROR, null);
                    break;
            }
        }
    }

    private void setProductDetails(@Nullable List<ProductInfo> appGalleryProductInfos) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setProductDetails: appGalleryProductInfos=" + appGalleryProductInfos);
        }


        mProducts.clear();

        if (appGalleryProductInfos == null) {
            return;
        }

        for (ProductInfo agProductInfo : appGalleryProductInfos) {
            List<ProductDetails.SubscriptionOfferDetails> subscriptionOfferDetails = mapProductInfo(agProductInfo);
            mProducts.put(new ProductDetails(agProductInfo.getProductId(), subscriptionOfferDetails), agProductInfo);
        }
    }

    @NonNull
    private List<ProductDetails.SubscriptionOfferDetails> mapProductInfo(@NonNull ProductInfo agProductInfo) {

        List<ProductDetails.PricingPhase> pricingPhases = new ArrayList<>();

        if (agProductInfo.getSubFreeTrialPeriod() != null && !agProductInfo.getSubFreeTrialPeriod().isEmpty()) {
            pricingPhases.add(new ProductDetails.PricingPhase(agProductInfo.getSubSpecialPrice(), agProductInfo.getSubSpecialPriceMicros()));
        }

        pricingPhases.add(new ProductDetails.PricingPhase(agProductInfo.getPrice(), agProductInfo.getMicrosPrice()));

        return Collections.singletonList(new ProductDetails.SubscriptionOfferDetails(pricingPhases));
    }

    @Nullable
    private Purchase mapPurchase(@NonNull String inAppPurchaseDataString) {
        InAppPurchaseData inAppPurchaseData;
        try {
            inAppPurchaseData = new InAppPurchaseData(inAppPurchaseDataString);
        } catch (JSONException e) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Couldn't parse InAppPurchaseData: " + inAppPurchaseDataString, e);
            }
            return null;
        }

        String orderId = inAppPurchaseData.getSubscriptionId();
        String purchaseToken = inAppPurchaseData.getPurchaseToken();
        String productInfo = inAppPurchaseData.getProductId();
        long purchaseTime = inAppPurchaseData.getPurchaseTime();

        return new Purchase(Purchase.PurchaseState.PURCHASED, orderId, purchaseToken, Collections.singletonList(productInfo), purchaseTime);
    }
}
