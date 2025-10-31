/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.services;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.ConnectivityService;
import org.twinlife.twinlife.ProxyDescriptor;
import org.twinlife.twinlife.SNIProxyDescriptor;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.List;

public class ProxyService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ProxyService";
    private static final boolean DEBUG = false;

    public interface Observer extends AbstractTwinmeService.Observer {

        void onAddProxy(@NonNull SNIProxyDescriptor proxyDescriptor);

        void onDeleteProxy(@NonNull ProxyDescriptor proxyDescriptor);

        void onErrorAddProxy();

        void onErrorAlreadyUsed();

        void onErrorLimitReached();
    }

    @Nullable
    private Observer mObserver;

    private int mProxyPosition = -1;

    public ProxyService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull ProxyService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ResetConversationService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void verifyProxyURI(@NonNull Uri proxyUri, int position) {
        if (DEBUG) {
            Log.d(LOG_TAG, "verifyProxyURI: proxyUri=" + proxyUri);
        }

        mProxyPosition = position;
        parseURI(proxyUri, this::onParseURI);
    }

    public void deleteProxy(@NonNull ProxyDescriptor proxyDescriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteProxy: " + proxyDescriptor);
        }

        mTwinmeContext.execute(() -> {
            List<ProxyDescriptor> proxies = mTwinmeContext.getConnectivityService().getUserProxies();
            proxies.remove(proxyDescriptor);
            mTwinmeContext.getConnectivityService().setUserProxies(proxies);

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onDeleteProxy(proxyDescriptor);
                }
            });
        });
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    //
    // Private methods
    //

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();
    }

    private void onParseURI(@NonNull BaseService.ErrorCode errorCode, @Nullable TwincodeURI twincodeUri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onParseURI: errorCode=" + errorCode + " twincodeUri=" + twincodeUri);
        }

        if (errorCode != BaseService.ErrorCode.SUCCESS || twincodeUri == null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onErrorAddProxy();
                }
            });
        } else {
            final List<ProxyDescriptor> proxies = mTwinmeContext.getConnectivityService().getUserProxies();
            if (proxies.size() >= ConnectivityService.MAX_PROXIES && mProxyPosition == -1) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onErrorLimitReached();
                    }
                });
                return;
            }

            for (ProxyDescriptor proxyDescriptor : proxies) {
                if (proxyDescriptor.getDescriptor().equals(twincodeUri.uri)) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onErrorAlreadyUsed();
                        }
                    });
                    return;
                }
            }

            SNIProxyDescriptor proxyDescriptor = SNIProxyDescriptor.create(twincodeUri.uri);
            if (proxyDescriptor == null) {
                if (mObserver != null) {
                    mObserver.onErrorAddProxy();
                }
                return;
            }

            if (mProxyPosition != -1) {
                proxies.set(mProxyPosition, proxyDescriptor);
            } else {
                proxies.add(proxyDescriptor);
            }

            mTwinmeContext.getConnectivityService().setUserProxies(proxies);

            if (mObserver != null) {
                mObserver.onAddProxy(proxyDescriptor);
            }
        }
    }

    @Override
    protected void onError(int operationId, BaseService.ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == BaseService.ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
