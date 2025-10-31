/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AccountMigrationService;
import org.twinlife.twinlife.AccountMigrationService.State;
import org.twinlife.twinlife.AccountMigrationService.Status;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.RepositoryService;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.AccountMigration;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

/**
 * Account scanner migration service.
 * <p>
 * This service is used when the user scans the account migration QR-code to:
 * - create the local AccountMigration twincode and object,
 * - get the peer account migration twincode,
 * - bind the two account migration twincodes.
 */
public class AccountMigrationScannerService extends AbstractTwinmeService {
    private static final String LOG_TAG = "MigrationScannerService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int CREATE_ACCOUNT_MIGRATION = 1 << 2;
    private static final int CREATE_ACCOUNT_MIGRATION_DONE = 1 << 3;
    private static final int GET_TWINCODE = 1 << 4;
    private static final int GET_TWINCODE_DONE = 1 << 5;
    private static final int BIND_ACCOUNT_MIGRATION = 1 << 6;
    private static final int BIND_ACCOUNT_MIGRATION_DONE = 1 << 7;

    public interface Observer extends AbstractTwinmeService.Observer, TwincodeObserver {

        void onGetDefaultProfile(@NonNull Profile profile);

        void onGetDefaultProfileNotFound();

        void onCreateAccountMigration(@Nullable AccountMigration accountMigration,
                                      @Nullable TwincodeURI twincodeURI);

        void onAccountMigrationConnected(@NonNull UUID accountMigrationId);

        void onHasRelations();
    }

    private class AccountMigrationServiceObserver extends AccountMigrationService.DefaultServiceObserver {

        @Override
        public void onStatusChange(@NonNull UUID accountMigrationId, @NonNull Status status) {
            if (DEBUG) {
                Log.d(LOG_TAG, "AccountMigrationServiceObserver.onStatusChange accountMigrationId=" + accountMigrationId
                        + " status=" + status);
            }

            AccountMigrationScannerService.this.onStatusChange(accountMigrationId, status);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private int mWork;

    private boolean mHasRelations;
    private UUID mTwincodeOutboundId;
    private TwincodeOutbound mTwincodeOutbound;
    private AccountMigration mAccountMigration;
    private final AccountMigrationServiceObserver mAccountMigrationServiceObserver;

    public AccountMigrationScannerService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                          @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "AccountMigrationScannerService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mAccountMigrationServiceObserver = new AccountMigrationServiceObserver();

        mWork = CREATE_ACCOUNT_MIGRATION;
        mHasRelations = false;
        mTwinmeContext.setObserver(mTwinmeContextObserver);
        showProgressIndicator();

        // If we are not connected, force an immediate connection retry.
        if (!twinmeContext.isConnected()) {
            twinmeContext.connect();
        }
    }

    public AccountMigrationScannerService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                          @NonNull UUID twincodeOutboundId, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "AccountMigrationScannerService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " twincodeOutboundId=" + twincodeOutboundId + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mAccountMigrationServiceObserver = new AccountMigrationServiceObserver();
        mTwincodeOutboundId = twincodeOutboundId;
        mWork = GET_TWINCODE;

        mTwinmeContext.setObserver(mTwinmeContextObserver);
        showProgressIndicator();

        // If we are not connected, force an immediate connection retry.
        if (!twinmeContext.isConnected()) {
            twinmeContext.connect();
        }
    }

    public void getTwincodeOutbound(@NonNull UUID twincodeOutboundId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getTwincodeOutbound: twincodeOutboundId=" + twincodeOutboundId);
        }

        mTwincodeOutboundId = twincodeOutboundId;
        mWork |= GET_TWINCODE;
        mState &= ~(GET_TWINCODE | GET_TWINCODE_DONE);

        showProgressIndicator();
        startOperation();
    }

    public void bindAccountMigration(@NonNull TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "bindAccountMigration twincodeOutbound=" + twincodeOutbound);
        }

        mTwincodeOutbound = twincodeOutbound;
        mWork |= BIND_ACCOUNT_MIGRATION;
        mState &= ~(BIND_ACCOUNT_MIGRATION | BIND_ACCOUNT_MIGRATION_DONE);

        showProgressIndicator();
        startOperation();
    }

    public void createAccountMigration() {
        if (DEBUG) {
            Log.d(LOG_TAG, "createAccountMigration");
        }

        mWork |= CREATE_ACCOUNT_MIGRATION | BIND_ACCOUNT_MIGRATION;
        mState &= ~(CREATE_ACCOUNT_MIGRATION | CREATE_ACCOUNT_MIGRATION_DONE | BIND_ACCOUNT_MIGRATION | BIND_ACCOUNT_MIGRATION_DONE);

        showProgressIndicator();
        startOperation();
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mIsTwinlifeReady) {
            mTwinmeContext.getAccountMigrationService().removeServiceObserver(mAccountMigrationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        mTwinmeContext.getAccountMigrationService().addServiceObserver(mAccountMigrationServiceObserver);

        super.onTwinlifeReady();
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
        }
    }

    //
    // Private methods
    //

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

        //
        // Step 1: create the account migration object and its twincode.
        //
        if ((mWork & CREATE_ACCOUNT_MIGRATION) != 0) {
            if ((mState & CREATE_ACCOUNT_MIGRATION) == 0) {
                mState |= CREATE_ACCOUNT_MIGRATION;

                mTwinmeContext.createAccountMigration(this::onCreateAccountMigration);
                return;
            }
            if ((mState & CREATE_ACCOUNT_MIGRATION_DONE) == 0) {
                return;
            }
        }

        // We must get the account migration twincode.
        if ((mWork & GET_TWINCODE) != 0 && mTwincodeOutboundId != null) {
            if ((mState & GET_TWINCODE) == 0) {
                mState |= GET_TWINCODE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwincodeOutboundService.getTwincode: twincodeOutboundId=" + mTwincodeOutboundId);
                }
                mTwinmeContext.getTwincodeOutboundService().getTwincode(mTwincodeOutboundId, TwincodeOutboundService.REFRESH_PERIOD,
                        this::onGetTwincodeOutbound);
                return;
            }
            if ((mState & GET_TWINCODE_DONE) == 0) {
                return;
            }
        }

        // We must bind the account migration with the peer twincode.
        if ((mWork & BIND_ACCOUNT_MIGRATION) != 0 && mTwincodeOutbound != null && mAccountMigration != null) {
            if ((mState & BIND_ACCOUNT_MIGRATION) == 0) {
                mState |= BIND_ACCOUNT_MIGRATION;

                if (DEBUG) {
                    Log.d(LOG_TAG, "TwincodeOutboundService.bindDeviceMigration: twincodeOutboundId=" + mTwincodeOutboundId);
                }
                mTwinmeContext.bindAccountMigration(mAccountMigration, mTwincodeOutbound, (ErrorCode status, AccountMigration accountMigration) -> {
                    if (status == ErrorCode.SUCCESS && accountMigration != null) {
                        mAccountMigration = accountMigration;
                        runOnUiThread(() -> {
                            if (mObserver != null) {
                                mObserver.onAccountMigrationConnected(mAccountMigration.getId());
                            }
                        });
                    }
                    mState |= BIND_ACCOUNT_MIGRATION_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & BIND_ACCOUNT_MIGRATION_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    private void onGetCurrentSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;

        // Look if we have some contacts, or groups, or click-to-call.
        // We don't care for Space, Profile and other objects.
        final RepositoryService repositoryService = mTwinmeContext.getRepositoryService();
        mHasRelations = repositoryService.hasObjects(Contact.SCHEMA_ID);
        if (!mHasRelations) {
            mHasRelations = repositoryService.hasObjects(Group.SCHEMA_ID);
            if (!mHasRelations) {
                mHasRelations = repositoryService.hasObjects(CallReceiver.SCHEMA_ID);
            }
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                if (space != null && space.getProfile() != null) {
                    mObserver.onGetDefaultProfile(space.getProfile());
                } else {
                    mObserver.onGetDefaultProfileNotFound();
                }
                if (mHasRelations) {
                    mObserver.onHasRelations();
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
            runOnGetTwincode(mObserver, twincodeOutbound, null);

        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetTwincodeNotFound(mObserver);

        } else {
            onError(GET_TWINCODE, errorCode, null);
        }

        onOperation();
    }

    private void onCreateAccountMigration(@NonNull ErrorCode errorCode, @Nullable AccountMigration accountMigration) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateAccountMigration: errorCode=" + errorCode + " accountMigration=" + accountMigration);
        }

        mState |= CREATE_ACCOUNT_MIGRATION_DONE;
        if (errorCode == ErrorCode.SUCCESS && accountMigration != null) {
            mAccountMigration = accountMigration;

            final TwincodeOutbound twincodeOutbound = accountMigration.getTwincodeOutbound();
            if (twincodeOutbound != null) {
                mTwinmeContext.getTwincodeOutboundService().createURI(TwincodeURI.Kind.AccountMigration, twincodeOutbound, this::onCreateURI);
            }
        } else {
            onError(CREATE_ACCOUNT_MIGRATION, errorCode, null);
        }
        onOperation();
    }

    private void onCreateURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateAccountMigration: errorCode=" + errorCode + " uri=" + uri);
        }

        runOnUiThread(() -> {
            if (mObserver != null && mAccountMigration != null) {
                mObserver.onCreateAccountMigration(mAccountMigration, uri);
            }
        });
        onOperation();
    }

    private void onStatusChange(@NonNull UUID accountMigrationId, @NonNull Status status) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStatusChange accountMigrationId=" + accountMigrationId + " status=" + status);
        }

        if (status.getState() == State.NEGOTIATE) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onAccountMigrationConnected(accountMigrationId);
                }
            });
            onOperation();
        }
    }

    @Override
    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
