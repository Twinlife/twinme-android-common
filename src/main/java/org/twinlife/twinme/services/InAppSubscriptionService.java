/*
 *  Copyright (c) 2022-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AccountService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinme.TwinmeApplication;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

public class InAppSubscriptionService extends AbstractTwinmeService {
    private static final String LOG_TAG = "InAppSubscriptionS...";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int SUBSCRIBE_FEATURE = 1 << 2;
    private static final int SUBSCRIBE_FEATURE_DONE = 1 << 3;
    private static final int CANCEL_FEATURE = 1 << 4;
    private static final int CANCEL_FEATURE_DONE = 1 << 5;

    public interface Observer extends AbstractTwinmeService.Observer {

        void onGetDefaultProfile(@NonNull Profile profile);

        void onGetDefaultProfileNotFound();

        void onSubscribeSuccess();

        void onSubscribeCancel();

        void onSubscribeFailed(@NonNull ErrorCode errorCode);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateProfile(long requestId, @NonNull Profile profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateProfile: requestId=" + requestId + " profile=" + profile);
            }

            InAppSubscriptionService.this.onGetCurrentSpace(ErrorCode.SUCCESS, profile.getSpace());
        }
    }

    private class InAppSubscriptionAccountServiceObserver extends AccountService.DefaultServiceObserver {

        @Override
        public void onSubscribeUpdate(long requestId, @NonNull ErrorCode errorCode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "InAppSubscriptionAccountServiceObserver.onSubscribeUpdate: requestId=" + requestId + " errorCode=" + errorCode);
            }

            InAppSubscriptionService.this.onSubscribeUpdate(errorCode);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    private String mProductId;
    private String mPurchaseToken;
    private String mPurchaseOrderId;

    private final InAppSubscriptionAccountServiceObserver mInAppSubscriptionAccountServiceObserver;

    public InAppSubscriptionService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull InAppSubscriptionService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "DeleteAccountService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mInAppSubscriptionAccountServiceObserver = new InAppSubscriptionAccountServiceObserver();
        mTwinmeContextObserver = new TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
        showProgressIndicator();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getAccountService().addServiceObserver(mInAppSubscriptionAccountServiceObserver);
    }

    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;

            if ((mState & SUBSCRIBE_FEATURE) != 0 && (mState & SUBSCRIBE_FEATURE_DONE) == 0) {
                mState &= ~SUBSCRIBE_FEATURE;
            }
            if ((mState & CANCEL_FEATURE) != 0 && (mState & CANCEL_FEATURE_DONE) == 0) {
                mState &= ~CANCEL_FEATURE;
            }
        }
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getAccountService().removeServiceObserver(mInAppSubscriptionAccountServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    public void subscribeFeature(@NonNull String productId, @NonNull String purchaseToken, @NonNull String purchaseOrderId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "subscribeFeature: " + productId + " purchaseToken = " + purchaseToken + " purchaseOrderId = " + purchaseOrderId);
        }

        mProductId = productId;
        mPurchaseToken = purchaseToken;
        mPurchaseOrderId = purchaseOrderId;

        mWork = SUBSCRIBE_FEATURE;
        mState &= ~(SUBSCRIBE_FEATURE | SUBSCRIBE_FEATURE_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void cancelFeature(@NonNull String purchaseToken, @NonNull String purchaseOrderId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "cancelFeature: " + purchaseToken + " purchaseOrderId = " + purchaseOrderId);
        }

        mPurchaseToken = purchaseToken;
        mPurchaseOrderId = purchaseOrderId;

        mWork = CANCEL_FEATURE;
        mState &= ~(CANCEL_FEATURE | CANCEL_FEATURE_DONE);
        showProgressIndicator();
        startOperation();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: get the current space.
        //

        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            mTwinmeContext.getCurrentSpace(this::onGetCurrentSpace);
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        if ((mWork & SUBSCRIBE_FEATURE) != 0) {

            if ((mState & SUBSCRIBE_FEATURE) == 0) {
                mState |= SUBSCRIBE_FEATURE;

                long requestId = newOperation(SUBSCRIBE_FEATURE);
                mTwinmeContext.getAccountService().subscribeFeature(requestId, AccountService.MerchantIdentification.MERCHANT_GOOGLE, mProductId, mPurchaseToken, mPurchaseOrderId);
                return;
            }
            if ((mState & SUBSCRIBE_FEATURE_DONE) == 0) {
                return;
            }
        }
        if ((mWork & CANCEL_FEATURE) != 0) {

            if ((mState & CANCEL_FEATURE) == 0) {
                mState |= CANCEL_FEATURE;

                long requestId = newOperation(CANCEL_FEATURE);
                mTwinmeContext.getAccountService().cancelFeature(requestId, AccountService.MerchantIdentification.MERCHANT_EXTERNAL, mPurchaseToken, "");
                return;
            }
            if ((mState & CANCEL_FEATURE_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }

    private void onGetCurrentSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                if (space != null && space.getProfile() != null) {
                    mObserver.onGetDefaultProfile(space.getProfile());
                } else {
                    mObserver.onGetDefaultProfileNotFound();
                }
            }
        });
        onOperation();
    }

    private void onSubscribeUpdate(@NonNull ErrorCode errorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSubscribeUpdate: " + errorCode);
        }

        // When we are offline or failed to send the request, we must retry.
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        mState |= SUBSCRIBE_FEATURE_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                if (errorCode == ErrorCode.SUCCESS) {
                    if (mTwinmeApplication.isFeatureSubscribed(TwinmeApplication.Feature.GROUP_CALL)) {
                        mObserver.onSubscribeSuccess();
                    } else {
                        mObserver.onSubscribeCancel();
                    }
                } else {
                    mObserver.onSubscribeFailed(errorCode);
                }
            }
        });
        onOperation();
    }
}