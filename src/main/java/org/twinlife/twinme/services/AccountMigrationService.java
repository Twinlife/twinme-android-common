/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AccountMigrationService.QueryInfo;
import org.twinlife.twinlife.AccountMigrationService.State;
import org.twinlife.twinlife.AccountMigrationService.Status;
import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConnectionStatus;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.RepositoryService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinlife.util.Version;
import org.twinlife.twinme.NotificationCenter;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.AccountMigration;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.ui.Intents;
import org.twinlife.twinme.util.TwinmeAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Account migration foreground service.
 */
public class AccountMigrationService extends Service {
    private static final String LOG_TAG = "AccountMigrationService";
    private static final boolean DEBUG = false;

    private static final int GET_ACCOUNT_MIGRATION = 1;
    private static final int GET_ACCOUNT_MIGRATION_DONE = 1 << 1;
    private static final int GET_INCOMING_ACCOUNT_MIGRATION = 1 << 2;
    private static final int GET_INCOMING_ACCOUNT_MIGRATION_DONE = 1 << 3;
    private static final int QUERY_STAT = 1 << 4;
    private static final int QUERY_STAT_DONE = 1 << 5;
    private static final int OUTGOING_MIGRATION = 1 << 6;
    private static final int OUTGOING_MIGRATION_DONE = 1 << 7;
    private static final int ACCEPT_MIGRATION = 1 << 8;
    private static final int ACCEPT_MIGRATION_DONE = 1 << 9;
    private static final int START_MIGRATION = 1 << 10;
    private static final int TERMINATE_PHASE1 = 1 << 11;
    private static final int TERMINATE_PHASE1_DONE = 1 << 12;
    private static final int CANCEL_MIGRATION = 1 << 13;
    private static final int DELETE_MIGRATION = 1 << 14;
    private static final int DELETE_MIGRATION_DONE = 1 << 15;
    private static final int TERMINATE_PHASE2 = 1 << 16;
    private static final int TERMINATE_PHASE2_DONE = 1 << 17;
    private static final int FINAL_SHUTDOWN = 1 << 18;
    private static final int FINAL_SHUTDOWN_DONE = 1 << 19;
    private static final int STOP_SERVICE = 1 << 20;

    /**
     * Actions supported by the service.
     */
    public static final String ACTION_INCOMING_MIGRATION = "org.twinlife.device.android.twinme.INCOMING_MIGRATION";
    public static final String ACTION_OUTGOING_MIGRATION = "org.twinlife.device.android.twinme.OUTGOING_MIGRATION";
    public static final String ACTION_START_MIGRATION = "org.twinlife.device.android.twinme.START_MIGRATION";
    public static final String ACTION_CANCEL_MIGRATION = "org.twinlife.device.android.twinme.CANCEL_MIGRATION";
    public static final String ACTION_STATE_MIGRATION = "org.twinlife.device.android.twinme.STATE_MIGRATION";
    public static final String ACTION_ACCEPT_MIGRATION = "org.twinlife.device.android.twinme.ACCEPT_MIGRATION";

    /**
     * Action parameters.
     */
    public static final String PARAM_ACCOUNT_MIGRATION_ID = "accountMigrationId";
    public static final String PARAM_PEER_CONNECTION_ID = "peerConnectionId";

    /**
     * Event message types.
     */
    public static final String MESSAGE_STATE = "state";
    public static final String MESSAGE_ERROR = "error";
    public static final String MESSAGE_INCOMING = "incoming";

    /**
     * Event content attributes.
     */
    public static final String MIGRATION_SERVICE_EVENT = "event";
    public static final String MIGRATION_SERVICE_STATE = "migrationState";
    public static final String MIGRATION_STATUS = "status";
    public static final String MIGRATION_PEER_QUERY_INFO = "peerQueryInfo";
    public static final String MIGRATION_LOCAL_QUERY_INFO = "localQueryInfo";
    public static final String MIGRATION_DEVICE_ID = "deviceMigrationId";
    public static final String MIGRATION_START_TIME = "startTime";
    public static final String MIGRATION_PEER_VERSION = "peerVersion";
    public static final String MIGRATION_PEER_HAS_CONTACTS = "peerHasContacts";

    private class TwinmeContextObserver extends TwinmeContext.DefaultObserver {

        @Override
        public void onTwinlifeReady() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeReady");
            }

            AccountMigrationService.this.onTwinlifeReady();
        }

        @Override
        public void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onConnectionStatusChange");
            }

            AccountMigrationService.this.onConnectionStatusChange(connectionStatus);
        }

        @Override
        public void onUpdateAccountMigration(long requestId, @NonNull AccountMigration accountMigration) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateAccountMigration");
            }

            UUID accountMigrationId = accountMigration.getId();
            if (!accountMigrationId.equals(mAccountMigrationId) && !accountMigrationId.equals(mIncomingAccountMigrationId)) {
                return;
            }

            AccountMigrationService.this.onUpdateAccountMigration(accountMigration);
            onOperation();
        }

        @Override
        public void onDeleteAccountMigration(long requestId, @NonNull UUID accountMigrationId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteAccountMigration");
            }

            if (!accountMigrationId.equals(mAccountMigrationId) && !accountMigrationId.equals(mIncomingAccountMigrationId)) {
                return;
            }

            AccountMigrationService.this.onDeleteAccountMigration(accountMigrationId);
            onOperation();
        }
    }

    private class AccountMigrationServiceObserver extends org.twinlife.twinlife.AccountMigrationService.DefaultServiceObserver {

        @Override
        public void onQueryStats(long requestId, @NonNull QueryInfo peerInfo, @Nullable QueryInfo localInfo) {
            if (DEBUG) {
                Log.d(LOG_TAG, "MigrationService.onQueryStats requestId=" + requestId
                        + " peerInfo=" + peerInfo + " localInfo=" + localInfo);
            }

            // onQueryStats() can be called two times: once with our requestId and another time with DEFAULT_REQUEST_ID.
            if (requestId != BaseService.DEFAULT_REQUEST_ID) {
                synchronized (mRequestIds) {
                    if (mRequestIds.remove(requestId) == null) {

                        return;
                    }
                }
            }

            AccountMigrationService.this.onQueryStats(peerInfo, localInfo);
            onOperation();
        }

        @Override
        public void onStatusChange(@NonNull UUID accountMigrationId, @NonNull Status status) {
            if (DEBUG) {
                Log.d(LOG_TAG, "MigrationService.onStatusChange accountMigrationId=" + accountMigrationId + " status=" + status);
            }

            if (!accountMigrationId.equals(mAccountMigrationId) && !accountMigrationId.equals(mIncomingAccountMigrationId)) {

                return;
            }

            if (AccountMigrationService.this.onStatusChange(accountMigrationId, status)) {
                onOperation();
            }
        }

        @Override
        public void onTerminateMigration(long requestId, @NonNull UUID accountMigrationId, boolean commit, boolean done) {
            if (DEBUG) {
                Log.d(LOG_TAG, "MigrationService.onTerminateMigration requestId=" + requestId
                        + " accountMigrationId=" + accountMigrationId + " commit=" + commit + " done=" + done);
            }

            if (!accountMigrationId.equals(mAccountMigrationId) && !accountMigrationId.equals(mIncomingAccountMigrationId)) {

                return;
            }

            Integer operation;
            synchronized (mRequestIds) {
                operation = mRequestIds.remove(requestId);
            }

            AccountMigrationService.this.onTerminateMigration(requestId, operation, commit, done);
            onOperation();
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "MigrationService.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId;
            synchronized (mRequestIds) {
                operationId = mRequestIds.remove(requestId);
                if (operationId == null) {

                    return;
                }
            }

            AccountMigrationService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    static private volatile AccountMigrationService sCurrent = null;

    private boolean mIsTwinlifeReady = false;
    private int mState = 0;
    private int mWork = 0;
    @NonNull
    @SuppressLint("UseSparseArrays")
    private final Map<Long, Integer> mRequestIds = new HashMap<>();
    private TwinmeContextObserver mTwinmeContextObserver;
    private AccountMigrationServiceObserver mAccountMigrationServiceObserver;
    private TwinmeContext mTwinmeContext;
    private org.twinlife.twinlife.AccountMigrationService mAccountAccountMigrationService;
    private NotificationCenter mNotificationCenter;
    @Nullable
    private AccountMigration mAccountMigration;
    @Nullable
    private AccountMigration mIncomingAccountMigration;
    @Nullable
    private QueryInfo mPeerQueryInfo;
    @Nullable
    private QueryInfo mLocalQueryInfo;
    @Nullable
    private UUID mAccountMigrationId;
    @Nullable
    private UUID mIncomingAccountMigrationId;
    @Nullable
    private UUID mIncomingPeerConnectionId;
    @Nullable
    private Pair<Version, Boolean> mPeerVersion;
    private int mNotificationId;
    private State mMigrationState = State.STARTING;
    private Status mStatus;
    private long mTerminateRequestId;
    private boolean mCommit = false;
    private boolean mInitiator = false;
    private boolean mAcceptAny = false;
    private long mStartTime = 0;
    private JobService.ProcessingLock mProcessingLock;
    private JobService.NetworkLock mNetworkLock;

    public static boolean isRunning() {

        AccountMigrationService service = sCurrent;
        if (service == null) {

            return false;
        }

        Status status = service.mStatus;
        if (status == null) {

            return false;
        }

        switch (status.getState()) {
            case ERROR:
            case STOPPED:
            case CANCELED:
            case TERMINATED:
                return false;

            default:
                return true;
        }
    }

    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreate");
        }

        sCurrent = this;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mAccountMigrationServiceObserver = new AccountMigrationServiceObserver();

        TwinmeApplicationImpl twinmeApplication = TwinmeApplicationImpl.getInstance(this);
        if (twinmeApplication != null) {
            mTwinmeContext = twinmeApplication.getTwinmeContext();

            // Get the power processing lock to tell the system we need the CPU.
            mProcessingLock = twinmeApplication.allocateProcessingLock();

            // We also need the network for the lifetime of this service.
            mNetworkLock = twinmeApplication.allocateNetworkLock();

            mNotificationCenter = mTwinmeContext.getNotificationCenter();

            mTwinmeContext.setObserver(mTwinmeContextObserver);

            mNotificationId = mNotificationCenter.startMigrationService(this, false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStartCommand");
        }

        if (intent == null || intent.getAction() == null) {

            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        switch (action) {
            case ACTION_INCOMING_MIGRATION:
                onActionIncomingMigration(intent);
                break;

            case ACTION_ACCEPT_MIGRATION:
                onActionAcceptMigration(intent);
                break;

            case ACTION_OUTGOING_MIGRATION:
                onActionOutgoingMigration(intent);
                break;

            case ACTION_START_MIGRATION:
                onActionStartMigration(intent);
                break;

            case ACTION_CANCEL_MIGRATION:
                onActionCancelMigration(intent);
                break;

            case ACTION_STATE_MIGRATION:
                onActionStateMigration(intent);
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBind");
        }

        // We don't provide binding, so return null
        return null;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDestroy");
        }

        sCurrent = null;
        if (mIsTwinlifeReady) {
            mAccountAccountMigrationService.removeServiceObserver(mAccountMigrationServiceObserver);
        }
        mTwinmeContext.removeObserver(mTwinmeContextObserver);

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationId > 0) {
            mNotificationCenter.cancel(mNotificationId);
        }

        if (mNetworkLock != null) {
            mNetworkLock.release();
        }

        if (mProcessingLock != null) {
            mProcessingLock.release();
        }

        super.onDestroy();
    }

    private void onActionIncomingMigration(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onIncomingMigration intent=" + intent);
        }

        mNotificationId = mNotificationCenter.startMigrationService(this, true);

        UUID peerConnectionId = Utils.UUIDFromString(intent.getStringExtra(PARAM_PEER_CONNECTION_ID));
        UUID accountMigrationId = Utils.UUIDFromString(intent.getStringExtra(PARAM_ACCOUNT_MIGRATION_ID));
        if (accountMigrationId == null || peerConnectionId == null) {
            return;
        }

        // We must accept all incoming migration: errors are managed by the AccountMigrationService
        // which can terminate this incoming P2P connection if another account migration is already in progress.
        mWork |= ACCEPT_MIGRATION;
        mState &= ~ACCEPT_MIGRATION;
        mIncomingAccountMigrationId = accountMigrationId;
        mIncomingPeerConnectionId = peerConnectionId;
        mTwinmeContext.execute(this::onOperation);
    }

    private void onActionAcceptMigration(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionAcceptMigration intent=" + intent);
        }

        mAcceptAny = true;
    }

    private void onActionOutgoingMigration(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionOutgoingMigration intent=" + intent);
        }

        UUID accountMigrationId = Utils.UUIDFromString(intent.getStringExtra(PARAM_ACCOUNT_MIGRATION_ID));
        if (accountMigrationId == null || accountMigrationId.equals(mIncomingAccountMigrationId)) {

            sendMessage(MESSAGE_STATE);
            return;
        }

        mWork |= OUTGOING_MIGRATION;
        mAccountMigrationId = accountMigrationId;
        mTwinmeContext.execute(this::onOperation);
        sendMessage(MESSAGE_STATE);
    }

    private void onActionStartMigration(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStartMigration intent=" + intent);
        }

        if (mAccountMigration == null) {

            return;
        }

        mWork |= START_MIGRATION;
        mInitiator = true;
        mTwinmeContext.execute(this::onOperation);
    }

    private void onActionCancelMigration(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCancelMigration intent=" + intent);
        }

        mWork |= CANCEL_MIGRATION | DELETE_MIGRATION | STOP_SERVICE;
        mWork &= ~(OUTGOING_MIGRATION | ACCEPT_MIGRATION);
        mState &= ~(CANCEL_MIGRATION);
        mMigrationState = State.CANCELED;
        sendMessage(MESSAGE_STATE);
        mTwinmeContext.execute(this::onOperation);
    }

    private void onActionStateMigration(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStateMigration intent=" + intent);
        }

        sendMessage(MESSAGE_STATE);
    }

    private void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        mIsTwinlifeReady = true;
        mAccountAccountMigrationService = mTwinmeContext.getAccountMigrationService();
        mAccountAccountMigrationService.addServiceObserver(mAccountMigrationServiceObserver);
    }

    private void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionStatusChange " + connectionStatus);
        }

        sendMessage(MESSAGE_STATE);
    }

    private boolean isConnected() {

        return mStatus != null && mStatus.isConnected();
    }

    private boolean canAcceptIncomingMigration() {
        if (DEBUG) {
            Log.d(LOG_TAG, "canAcceptIncomingMigration");
        }

        if (mPeerVersion == null) {
            return false;
        }

        if (mAcceptAny) {
            return true;
        }

        // Look if we have some contacts, or groups, or click-to-call.
        // We don't care for Space, Profile and other objects.
        final RepositoryService repositoryService = mTwinmeContext.getRepositoryService();
        boolean hasRelations = repositoryService.hasObjects(Contact.SCHEMA_ID);
        if (!hasRelations) {
            hasRelations = repositoryService.hasObjects(Group.SCHEMA_ID);
            if (!hasRelations) {
                hasRelations = repositoryService.hasObjects(CallReceiver.SCHEMA_ID);
            }
        }

        // If the peer version is too old, there is a strong risk to loose data: if we send our database
        // it has a new format that is not compatible with the peer device application.
        // - if version match, we can proceed,
        // - if our version is newer and there is no relation, we can proceed,
        // - if our version is older and the peer has no relation, we can proceed.
        final Version supportedVersion = new Version(org.twinlife.twinlife.AccountMigrationService.VERSION);
        return (mPeerVersion.first.major == supportedVersion.major
                || (mPeerVersion.first.major < supportedVersion.major && !hasRelations)
                || (mPeerVersion.first.major > supportedVersion.major && Boolean.FALSE.equals(mPeerVersion.second)));
    }

    @SuppressWarnings("SameParameterValue")
    private long newOperation(int operationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "newOperation: operationId=" + operationId);
        }

        long requestId = mTwinmeContext.newRequestId();
        synchronized (mRequestIds) {
            mRequestIds.put(requestId, operationId);
        }

        return requestId;
    }

    private void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        if (Logger.ERROR) {
            Log.e(LOG_TAG, "onOperation state=" + mState + " migrationState=" + mMigrationState + " work=" + mWork + " init=" + mInitiator);
        }
        if ((mWork & CANCEL_MIGRATION) != 0) {
            if ((mState & CANCEL_MIGRATION) == 0) {
                mState |= CANCEL_MIGRATION;

                if (mAccountMigrationId != null) {
                    mAccountAccountMigrationService.cancelMigration(mAccountMigrationId);
                }
                if (mIncomingAccountMigrationId != null) {
                    mAccountAccountMigrationService.cancelMigration(mIncomingAccountMigrationId);
                }
            }
        }

        //
        // Step 1a: get the account migration object (outgoing mode).
        //
        if (mAccountMigrationId != null) {

            if ((mState & GET_ACCOUNT_MIGRATION) == 0) {
                mState |= GET_ACCOUNT_MIGRATION;

                mTwinmeContext.getAccountMigration(mAccountMigrationId, (ErrorCode status, AccountMigration accountMigration) -> {
                    mAccountMigration = accountMigration;
                    if (status != ErrorCode.SUCCESS || accountMigration == null) {
                        mAccountAccountMigrationService.cancelMigration(mAccountMigrationId);

                        // Send an error to inform the AccountMigrationActivity before stopping.
                        sendMessage(MESSAGE_ERROR);
                        mWork |= STOP_SERVICE;
                    } else {
                        TwincodeOutbound peerTwincode = accountMigration.getPeerTwincodeOutbound();
                        if (peerTwincode != null) {
                            mPeerVersion = TwinmeAttributes.getTwincodeAttributeAccountMigration(peerTwincode);
                        }
                    }
                    mState |= GET_ACCOUNT_MIGRATION_DONE;
                    onOperation();
                });
            }
            if ((mState & GET_ACCOUNT_MIGRATION_DONE) == 0) {
                return;
            }

        }


        //
        // Work action: start the outgoing migration P2P connection.
        //
        if (mAccountMigration != null && (mWork & OUTGOING_MIGRATION) != 0) {

            // Wait for the peer device to receive the invocation and send us back an invocation.
            final UUID peerTwincodeOutboundId = mAccountMigration.getPeerTwincodeOutboundId();
            final UUID twincodeOutboundId = mAccountMigration.getTwincodeOutboundId();
            if (!mAccountMigration.isBound() || peerTwincodeOutboundId == null || twincodeOutboundId == null) {
                return;
            }

            if ((mState & OUTGOING_MIGRATION) == 0) {
                mState |= OUTGOING_MIGRATION;

                long requestId = newOperation(OUTGOING_MIGRATION);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.startMigration: requestId=" + requestId + " deviceMigration=" + mAccountMigration);
                }
                mAccountAccountMigrationService.outgoingStartMigration(requestId, mAccountMigration.getId(),
                        peerTwincodeOutboundId, twincodeOutboundId);
            }
        }

        //
        // Step 1b: get the account migration object (incoming mode).
        //
        if (mIncomingAccountMigrationId != null) {

            if ((mState & GET_INCOMING_ACCOUNT_MIGRATION) == 0) {
                mState |= GET_INCOMING_ACCOUNT_MIGRATION;

                mTwinmeContext.getAccountMigration(mIncomingAccountMigrationId, (ErrorCode status, AccountMigration accountMigration) -> {
                    mIncomingAccountMigration = accountMigration;
                    if (status != ErrorCode.SUCCESS || accountMigration == null) {
                        mAccountAccountMigrationService.cancelMigration(mIncomingAccountMigrationId);

                        // Send an error to inform the AccountMigrationActivity before stopping.
                        sendMessage(MESSAGE_ERROR);
                        mWork |= STOP_SERVICE;
                    } else {
                        TwincodeOutbound peerTwincode = accountMigration.getPeerTwincodeOutbound();
                        if (peerTwincode != null) {
                            mPeerVersion = TwinmeAttributes.getTwincodeAttributeAccountMigration(peerTwincode);
                        }
                    }
                    mState |= GET_INCOMING_ACCOUNT_MIGRATION_DONE;
                    onOperation();
                });
            }
            if ((mState & GET_INCOMING_ACCOUNT_MIGRATION_DONE) == 0) {
                return;
            }

            //
            // Work action: accept the incoming P2P connection for the account migration.
            //
            if ((mWork & ACCEPT_MIGRATION) != 0 && mIncomingPeerConnectionId != null && mIncomingAccountMigration != null) {
                if ((mState & ACCEPT_MIGRATION) == 0) {
                    mState |= ACCEPT_MIGRATION;

                    if (canAcceptIncomingMigration()) {
                        mAccountAccountMigrationService.incomingStartMigration(mIncomingPeerConnectionId, mIncomingAccountMigrationId,
                                mIncomingAccountMigration.getPeerTwincodeOutboundId(), mIncomingAccountMigration.getTwincodeOutboundId());
                    } else {
                        sendMessage(MESSAGE_INCOMING);
                    }
                }
            }
        }

        //
        // Work action: query the peer's device stats.
        //
        if (mAccountMigration != null && isConnected() && (mWork & QUERY_STAT) != 0) {
            if ((mState & QUERY_STAT) == 0) {
                mState |= QUERY_STAT;

                long requestId = newOperation(QUERY_STAT);
                mAccountAccountMigrationService.queryStats(requestId, Long.MAX_VALUE);
            }
            if ((mState & QUERY_STAT_DONE) == 0) {
                return;
            }
        }

        //
        // Work action: start the migration.
        //
        if (mAccountMigration != null && isConnected() && (mWork & START_MIGRATION) != 0) {
            if ((mState & START_MIGRATION) == 0) {
                mState |= START_MIGRATION;

                long requestId = newOperation(START_MIGRATION);
                mAccountAccountMigrationService.startMigration(requestId, Long.MAX_VALUE);
            }
        }

        //
        // Work step: send the terminate-migration message to proceed to termination phase1: we ask the peer to delete its twincode.
        //
        if (mAccountMigration != null && isConnected() && (mWork & TERMINATE_PHASE1) != 0) {
            if ((mState & TERMINATE_PHASE1) == 0) {
                mState |= TERMINATE_PHASE1;

                long requestId = newOperation(TERMINATE_PHASE1);
                mAccountAccountMigrationService.terminateMigration(requestId, mCommit, false);
                return;
            }

            if ((mState & TERMINATE_PHASE1_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: delete the device migration object.
        //
        if (mAccountMigration != null && (mWork & DELETE_MIGRATION) != 0) {
            if ((mState & DELETE_MIGRATION) == 0) {
                mState |= DELETE_MIGRATION;

                mTwinmeContext.deleteAccountMigration(mAccountMigration, (ErrorCode status, UUID deviceMigrationId) -> {
                    mState |= DELETE_MIGRATION_DONE;
                    onOperation();
                });
                return;
            }

            if ((mState & DELETE_MIGRATION_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: send the terminate-migration message to proceed to termination phase2: tell the peer we have done it.
        //
        if (mAccountMigration != null && isConnected() && (mWork & TERMINATE_PHASE2) != 0) {
            if ((mState & TERMINATE_PHASE2) == 0) {
                mState |= TERMINATE_PHASE2;

                mAccountAccountMigrationService.terminateMigration(mTerminateRequestId, mCommit, true);
                return;
            }

            if ((mState & TERMINATE_PHASE2_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: final shutdown to tell the peer we are going to close the P2P connection.
        //
        if (mAccountMigration != null && isConnected() && (mWork & FINAL_SHUTDOWN) != 0) {
            if ((mState & FINAL_SHUTDOWN) == 0) {
                mState |= FINAL_SHUTDOWN;

                long requestId = newOperation(FINAL_SHUTDOWN);
                mAccountAccountMigrationService.shutdownMigration(requestId);
            }

            if ((mState & FINAL_SHUTDOWN_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: stop the service when all the above is done (it must be last and callable even if mDeviceMigration is null).
        //
        if ((mWork & STOP_SERVICE) != 0) {
            if ((mState & STOP_SERVICE) == 0) {
                mState |= STOP_SERVICE;

                finish();
            }
        }
    }

    private void onUpdateAccountMigration(@NonNull AccountMigration accountMigration) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateAccountMigration accountMigration=" + accountMigration);
        }

        mAccountMigration = accountMigration;
    }

    private void onDeleteAccountMigration(@NonNull UUID accountMigrationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteAccountMigration accountMigrationId=" + accountMigrationId);
        }

        // If this is an expected delete, we are done.
        if ((mState & DELETE_MIGRATION) != 0) {

            return;
        }

        // This is an unexpected deleted, we must cancel the migration.
        if (!mAccountAccountMigrationService.cancelMigration(accountMigrationId)) {

            return;
        }

        mState |= STOP_SERVICE;
        mWork |= STOP_SERVICE;

        // And send a new state with canceled state so that the activity is aware of the cancel.
        mMigrationState = State.CANCELED;
        sendMessage(MESSAGE_STATE);
    }

    private void onQueryStats(@NonNull QueryInfo peerInfo, @Nullable QueryInfo localInfo) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onQueryStats peerInfo=" + peerInfo + " localInfo=" + localInfo);
        }

        mState |= QUERY_STAT_DONE;
        mPeerQueryInfo = peerInfo;
        mLocalQueryInfo = localInfo;
        sendMessage(MESSAGE_STATE);
    }

    private boolean onStatusChange(@NonNull UUID accountMigrationId, @NonNull Status status) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStatusChange accountMigrationId=" + accountMigrationId + " status=" + status);
        }

        State state = status.getState();
        if (state == State.LIST_FILES && mStartTime == 0) {
            mStartTime = SystemClock.elapsedRealtime();
        }

        // The service was started due to an incoming migration request, update the information once we are connected.
        // Update the state since the GET_ACCOUNT_MIGRATION operation is not necessary.
        if (mAccountMigration == null && accountMigrationId.equals(mIncomingAccountMigrationId)) {
            mState |= GET_ACCOUNT_MIGRATION | GET_ACCOUNT_MIGRATION_DONE;
            mAccountMigration = mIncomingAccountMigration;
            mAccountMigrationId = mIncomingAccountMigrationId;
        }
        if (mAccountMigration == null && state == State.NEGOTIATE) {
            Log.e(LOG_TAG, "No accountMigration object: " + mIncomingAccountMigrationId + " " + accountMigrationId);
        }

        // Detect when the P2P connection was restarted: we must cleanup our state
        // so that we can proceed with the new P2P connection.
        boolean needRestart = mMigrationState != state && mMigrationState != null
                && state == State.LIST_FILES && mMigrationState.ordinal() > state.ordinal();

        mMigrationState = state;
        mStatus = status;
        sendMessage(MESSAGE_STATE);

        if (state == State.NEGOTIATE || needRestart) {
            // Starting a new P2P connection and we are now connected.
            mState |= ACCEPT_MIGRATION_DONE | OUTGOING_MIGRATION_DONE;
            mState &= ~(QUERY_STAT | QUERY_STAT_DONE);
            mState &= ~(TERMINATE_PHASE1 | TERMINATE_PHASE1_DONE);
            mState &= ~(DELETE_MIGRATION | DELETE_MIGRATION_DONE);
            mState &= ~(TERMINATE_PHASE2 | TERMINATE_PHASE2_DONE);
            mState &= ~(FINAL_SHUTDOWN | FINAL_SHUTDOWN_DONE);
            mWork &= ~(TERMINATE_PHASE1 | TERMINATE_PHASE2 | DELETE_MIGRATION | FINAL_SHUTDOWN);
            mWork |= QUERY_STAT;
            return true;

        } else if (state == State.CANCELED) {

            // P2P connection canceled or migration canceled: delete the migration object.
            mState &= ~(DELETE_MIGRATION | DELETE_MIGRATION_DONE);
            mWork |= DELETE_MIGRATION | STOP_SERVICE;
            return true;

        } else if (state == State.TERMINATE && ((mWork & TERMINATE_PHASE1) == 0) && mInitiator) {

            // Start the terminate phase 1.
            mWork |= TERMINATE_PHASE1;
            mCommit = true;
            return true;

        } else if (state == State.STOPPED) {
            mWork |= STOP_SERVICE;
            return true;

        }

        // No state change!
        return false;
    }

    private void onTerminateMigration(long requestId, @Nullable Integer operation, boolean commit, boolean done) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminateMigration: requestId= " + requestId + " operation=" + operation + " commit=" + commit + " done=" + done);
        }

        if (Logger.ERROR) {
            Log.e(LOG_TAG, "onTerminateMigration: requestId= " + requestId + " operation=" + operation + " commit=" + commit + " done=" + done);
        }
        if (operation == null && !done) {
            mState |= TERMINATE_PHASE1 | TERMINATE_PHASE1_DONE;
            mWork |= FINAL_SHUTDOWN | STOP_SERVICE;
            mTerminateRequestId = requestId;

        } else if (operation == null && !mInitiator) {
            mState |= TERMINATE_PHASE2 | TERMINATE_PHASE2_DONE;
            mTerminateRequestId = requestId;

        } else if (operation != null && operation == TERMINATE_PHASE1) {
            mState |= TERMINATE_PHASE1_DONE;
            mTerminateRequestId = requestId;

        } else if (operation != null && operation == TERMINATE_PHASE2) {
            mState |= TERMINATE_PHASE2_DONE;
            mTerminateRequestId = requestId;

        }

        mCommit = commit;
        mWork |= DELETE_MIGRATION | TERMINATE_PHASE2;
    }

    private void onError(int operationId, ErrorCode errorCode, String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {

            return;
        }

        if (errorCode == ErrorCode.BAD_REQUEST) {
            // Send an error to inform the AccountMigrationActivity before stopping.
            sendMessage(MESSAGE_ERROR);
            mWork |= STOP_SERVICE;
        }
    }

    private void sendMessage(@NonNull String event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendMessage: event=" + event);
        }

        Intent intent = new Intent(Intents.INTENT_MIGRATION_SERVICE_MESSAGE);
        intent.setPackage(getPackageName());
        intent.putExtra(MIGRATION_SERVICE_EVENT, event);
        intent.putExtra(MIGRATION_START_TIME, mStartTime);
        if (mAccountMigrationId != null) {
            intent.putExtra(MIGRATION_DEVICE_ID, mAccountMigrationId);
        } else if (mIncomingAccountMigrationId != null) {
            intent.putExtra(MIGRATION_DEVICE_ID, mIncomingAccountMigrationId);
        }
        if (mStatus != null) {
            intent.putExtra(MIGRATION_STATUS, mStatus);
        }
        if (mPeerQueryInfo != null) {
            intent.putExtra(MIGRATION_PEER_QUERY_INFO, mPeerQueryInfo);
        }
        if (mLocalQueryInfo != null) {
            intent.putExtra(MIGRATION_LOCAL_QUERY_INFO, mLocalQueryInfo);
        }
        if (mMigrationState != null) {
            intent.putExtra(MIGRATION_SERVICE_STATE, mMigrationState);
        }
        if (mPeerVersion != null) {
            intent.putExtra(MIGRATION_PEER_VERSION, mPeerVersion.first.toString());
            intent.putExtra(MIGRATION_PEER_HAS_CONTACTS, mPeerVersion.second.booleanValue());
        }
        sendBroadcast(intent);
    }

    private void finish() {
        if (DEBUG) {
            Log.d(LOG_TAG, "finish");
        }

        stopForeground(true);

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationId > 0) {
            mNotificationCenter.cancel(mNotificationId);
        }
        stopSelf();
    }
}
