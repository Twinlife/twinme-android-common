/*
 *  Copyright (c) 2023-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AccountService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

public class InvitationSubscriptionService extends AbstractTwinmeService {
    private static final String LOG_TAG = "InvitationSubscript...";
    private static final boolean DEBUG = false;

    private static final int GET_SPACE = 1;
    private static final int GET_SPACE_DONE = 1 << 1;
    private static final int PARSE_URI = 1 << 2;
    private static final int PARSE_URI_DONE = 1 << 3;
    private static final int GET_TWINCODE = 1 << 4;
    private static final int GET_TWINCODE_DONE = 1 << 5;
    private static final int GET_TWINCODE_IMAGE = 1 << 6;
    private static final int GET_TWINCODE_IMAGE_DONE = 1 << 7;
    private static final int SUBSCRIBE_FEATURE = 1 << 8;
    private static final int SUBSCRIBE_FEATURE_DONE = 1 << 9;

    public interface Observer extends AbstractTwinmeService.Observer, SpaceObserver, TwincodeObserver {

        void onParseTwincodeURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI uri);

        void onSubscribeSuccess();

        void onSubscribeFailed(@NonNull ErrorCode errorCode);
    }

    private class InvitationSubscriptionAccountServiceObserver extends AccountService.DefaultServiceObserver {

        @Override
        public void onSubscribeUpdate(long requestId, @NonNull ErrorCode errorCode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "InvitationSubscriptionServiceObserver.onSubscribeUpdate: requestId=" + requestId + " errorCode=" + errorCode);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            InvitationSubscriptionService.this.onSubscribeUpdate(errorCode);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork = 0;
    private String mActivationCode;
    private String mTwincodeId;
    private String mProfileTwincodeOutboundId;

    private ImageId mTwincodeAvatarId;
    @Nullable
    private TwincodeOutbound mTwincodeOutbound;
    @Nullable
    private TwincodeURI mTwincodeURI;
    @NonNull
    private final Uri mUri;

    private final InvitationSubscriptionAccountServiceObserver mInvitationSubscriptionAccountServiceObserver;

    public InvitationSubscriptionService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                         @NonNull Uri uri, @NonNull InvitationSubscriptionService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "DeleteAccountService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mUri = uri;
        mInvitationSubscriptionAccountServiceObserver = new InvitationSubscriptionAccountServiceObserver();
        mTwinmeContextObserver = new TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getAccountService().addServiceObserver(mInvitationSubscriptionAccountServiceObserver);
    }

    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;

            if ((mState & GET_TWINCODE) != 0 && (mState & GET_TWINCODE_DONE) == 0) {
                mState &= ~GET_TWINCODE;
            }
            if ((mState & GET_TWINCODE_IMAGE) != 0 && (mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                mState &= ~GET_TWINCODE_IMAGE;
            }
            if ((mState & SUBSCRIBE_FEATURE) != 0 && (mState & SUBSCRIBE_FEATURE_DONE) == 0) {
                mState &= ~SUBSCRIBE_FEATURE;
            }
        }
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mIsTwinlifeReady) {

            // Remove the peer twincode from our local cache:
            // - we want to force a fetch to the server the next time it is required,
            //   (so that we take into account a profile refresh by the peer)
            // - we don't need it in our database.
            // - when we evict the twincode, the associated avatar is also evicted.
            // - IFF the twincode is referenced, it is not and must not be evicted!
            if (mTwincodeOutbound != null) {
                mTwinmeContext.getTwincodeOutboundService().evictTwincodeOutbound(mTwincodeOutbound);
            }

            if (mTwinmeContext.hasTwinlife()) {
                mTwinmeContext.getAccountService().removeServiceObserver(mInvitationSubscriptionAccountServiceObserver);
            }
        }

        mObserver = null;
        super.dispose();
    }


    public void subscribeFeature(@NonNull String twincodeId, @NonNull String activationCode, @NonNull String profileTwincodeOutboundId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "subscribeFeature: " + twincodeId + " activationCode = " + activationCode + " profileTwincodeOutboundId = " + profileTwincodeOutboundId);
        }

        mTwincodeId = twincodeId;
        mActivationCode = activationCode;
        mProfileTwincodeOutboundId = profileTwincodeOutboundId;

        mWork = SUBSCRIBE_FEATURE;
        mState &= ~(SUBSCRIBE_FEATURE | SUBSCRIBE_FEATURE_DONE);
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

        if ((mState & GET_SPACE) == 0) {
            mState |= GET_SPACE;

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                runOnGetSpace(mObserver, space, null);
                mState |= GET_SPACE_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2: parse the URI to build the TwincodeURI instance.
        //
        if ((mState & PARSE_URI) == 0) {
            mState |= PARSE_URI;
            mTwinmeContext.getTwincodeOutboundService().parseURI(mUri, this::onParseURI);
            return;
        }
        if ((mState & PARSE_URI_DONE) == 0) {
            return;
        }

        if (mTwincodeURI != null && mTwincodeURI.twincodeId != null) {
            if ((mState & GET_TWINCODE) == 0) {
                mState |= GET_TWINCODE;
                mTwinmeContext.getTwincodeOutboundService().getTwincode(mTwincodeURI.twincodeId, TwincodeOutboundService.REFRESH_PERIOD,
                        this::onGetTwincodeOutbound);
                return;
            }
            if ((mState & GET_TWINCODE_DONE) == 0) {
                return;
            }
        }

        //
        // Step 3: get the twincode avatar image.
        //
        if (mTwincodeAvatarId != null) {
            if ((mState & GET_TWINCODE_IMAGE) == 0) {
                mState |= GET_TWINCODE_IMAGE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "ImageService.getImage: imageId=" + mTwincodeAvatarId);
                }
                mTwinmeContext.getImageService().getImageFromServer(mTwincodeAvatarId, ImageService.Kind.THUMBNAIL, (ErrorCode errorCode, Bitmap image) -> {
                    // Second call to onGetTwincode to give the twincode name with the image.
                    if (image != null && mTwincodeOutbound != null) {
                        runOnGetTwincode(mObserver, mTwincodeOutbound, image);
                    }
                    mState |= GET_TWINCODE_IMAGE_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                return;
            }
        }

        if ((mWork & SUBSCRIBE_FEATURE) != 0) {

            if ((mState & SUBSCRIBE_FEATURE) == 0) {
                mState |= SUBSCRIBE_FEATURE;

                long requestId = newOperation(SUBSCRIBE_FEATURE);
                mTwinmeContext.getAccountService().subscribeFeature(requestId, AccountService.MerchantIdentification.MERCHANT_EXTERNAL, mTwincodeId, mActivationCode, mProfileTwincodeOutboundId);
                return;
            }
            if ((mState & SUBSCRIBE_FEATURE_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }

    private void onParseURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI twincodeUri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onParseURI: errorCode=" + errorCode + " twincodeUri=" + twincodeUri);
        }

        mState |= PARSE_URI_DONE;
        mTwincodeURI = twincodeUri;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onParseTwincodeURI(errorCode, twincodeUri);
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
                    mObserver.onSubscribeSuccess();
                } else {
                    mObserver.onSubscribeFailed(errorCode);
                }
            }
        });
        onOperation();
    }

    private void onGetTwincodeOutbound(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetTwincodeOutbound: twincodeOutbound=" + twincodeOutbound);
        }

        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;
            return;
        }

        mState |= GET_TWINCODE_DONE;
        if (errorCode == ErrorCode.SUCCESS && twincodeOutbound != null) {
            mTwincodeOutbound = twincodeOutbound;
            mTwincodeAvatarId = twincodeOutbound.getAvatarId();

            // First call to onGetTwincode to give the twincode name.
            runOnGetTwincode(mObserver, twincodeOutbound, null);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetTwincodeNotFound(mObserver);
        } else {
            onError(GET_TWINCODE, errorCode, null);
        }
        onOperation();
    }
}