/*
 *  Copyright (c) 2018-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinlife.ManagementService;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.configuration.AppFlavor;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.notificationCenter.PushTokenManager;
import org.twinlife.twinme.ui.TwinmeActivity;

/**
 * Service to delete the account.  The account deletion can take some time if there are several contacts, groups, profiles.
 */
public class DeleteAccountService extends AbstractTwinmeService {
    private static final String LOG_TAG = "DeleteAccountService";
    private static final boolean DEBUG = false;

    private static final int DELETE_FIREBASE_TOKEN = 1 << 1;
    private static final int DELETE_PUSH_TOKEN_DONE = 1 << 2;
    private static final int DELETE_FIREBASE_INSTALLATION = 1 << 3;
    private static final int DELETE_FIREBASE_INSTALLATION_DONE = 1 << 4;
    private static final int DELETE_ACCOUNT = 1 << 5;
    private static final int DELETE_ACCOUNT_DONE = 1 << 6;
    private static final int GET_CURRENT_SPACE = 1 << 7;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 8;

    public interface Observer extends AbstractTwinmeService.Observer, SpaceObserver {

        void onDeleteAccount();
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onTwinlifeReady() {
            if(AppFlavor.TWINME){
                //Twinme doesn't support spaces
                DeleteAccountService.this.mState |= GET_CURRENT_SPACE;
                DeleteAccountService.this.mState |= GET_CURRENT_SPACE_DONE;
            }

            super.onTwinlifeReady();
        }

        @Override
        public void onDeleteAccount(long requestId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteAccount: requestId=" + requestId);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            DeleteAccountService.this.onDeleteAccount();
        }
    }

    @Nullable
    private Observer mObserver;
    @NonNull
    private final PushTokenManager mPushTokenManager = PushTokenManager.INSTANCE;
    private int mState = 0;
    private int mWork = 0;

    public DeleteAccountService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "DeleteAccountService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    /**
     * The destructive and dangerous delete account operation.
     * <p>
     * - delete the contacts,
     * - delete the groups,
     * - delete the profiles,
     * - delete the system account.
     */
    public void deleteAccount() {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteAccount");
        }

        EventMonitor.info(LOG_TAG, "Delete account");

        mWork = DELETE_ACCOUNT;
        mState = 0;
        showProgressIndicator();

        mTwinmeContext.getNotificationCenter().cancelAll();
        startOperation();
    }

    @Override
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

        // We must delete the account.
        if ((mWork & DELETE_ACCOUNT) != 0) {

            //
            // Step 1: delete the firebase token (if a push is made with the token, the server will get the Unregistered error).
            //
            if ((mState & DELETE_FIREBASE_TOKEN) == 0) {
                mState |= DELETE_FIREBASE_TOKEN;

                if (mTwinmeContext.getManagementService().hasPushNotification()) {
                    if (mTwinmeApplication instanceof Context) {
                        try {
                            String pushProvider = mPushTokenManager.getPushProvider();
                            mPushTokenManager.deleteToken((Context) mTwinmeApplication, (errorCode) -> {
                                mState |= DELETE_PUSH_TOKEN_DONE;

                                Log.i(LOG_TAG, pushProvider + " token deleted: " + errorCode);

                                // Invalidate the notification token.
                                mTwinmeContext.getManagementService().setPushNotificationToken(pushProvider, "");

                                // Proceed with the next step.
                                onOperation();
                            });
                            return;

                        } catch (Exception exception) {
                            // Exceptions can be raised because we cannot trust FCM.
                            Log.w(LOG_TAG, "Firebase exception: " + exception.getMessage());

                            // Invalidate the notification token.
                            mTwinmeContext.getManagementService().setPushNotificationToken(ManagementService.PUSH_NOTIFICATION_FIREBASE_VARIANT, "");

                            mState |= DELETE_PUSH_TOKEN_DONE;
                        }
                    } else {
                        Log.e(LOG_TAG, "mTwinmeApplication " + mTwinmeApplication + " is not a Context, this should not happen");
                        mState |= DELETE_PUSH_TOKEN_DONE;
                    }
                } else {
                    mState |= DELETE_PUSH_TOKEN_DONE;
                }
            }
            if ((mState & DELETE_PUSH_TOKEN_DONE) == 0) {
                return;
            }

            //
            // Step 2: delete the firebase installation (this does not invalidate the token!!!!).
            //
            if ((mState & DELETE_FIREBASE_INSTALLATION) == 0) {
                mState |= DELETE_FIREBASE_INSTALLATION;

                if (mTwinmeContext.getManagementService().hasPushNotification()) {
                    mPushTokenManager.deleteInstallation((errorCode) -> {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Firebase installation deleted, result: " + errorCode);
                        }
                        mState |= DELETE_FIREBASE_INSTALLATION_DONE;
                        onOperation();
                    });
                } else {
                    mState |= DELETE_FIREBASE_INSTALLATION_DONE;
                }
            }
            if ((mState & DELETE_FIREBASE_INSTALLATION_DONE) == 0) {
                return;
            }

            //
            // Step 3: delete the account.
            //
            if ((mState & DELETE_ACCOUNT) == 0) {
                mState |= DELETE_ACCOUNT;

                long requestId = newOperation(DELETE_ACCOUNT);
                mTwinmeContext.deleteAccount(requestId);
                return;
            }
            if ((mState & DELETE_ACCOUNT_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        runOnUiThread(this::hideProgressIndicator);
    }

    private void onDeleteAccount() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteAccount");
        }

        mState |= DELETE_ACCOUNT_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteAccount();
            }
        });

        mTwinmeContext.removeAllDynamicShortcuts();

        onOperation();
    }

    private void onGetCurrentSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;

        runOnGetSpace(mObserver, space, null);
        onOperation();
    }
}
