/*
 *  Copyright (c) 2025-2026 twinlife SA.
 *
 *  All Rights Reserved.
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BackupInfo;
import org.twinlife.twinlife.BackupService.BackupState;
import org.twinlife.twinlife.BackupService.RestoreState;
import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinlife.backup.RestoreContent;
import org.twinlife.twinlife.backup.BackupHeaderInfo;
import org.twinlife.twinlife.backup.VerifyReport;
import org.twinlife.twinme.NotificationCenter;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.models.SpaceSettings;
import org.twinlife.twinme.ui.Intents;
import org.twinlife.twinme.utils.BackupStats;
import org.twinlife.twinme.utils.MnemonicCodeUtils;

import java.io.File;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BackupService extends Service {
    private static final String LOG_TAG = "BackupService";
    private static final boolean DEBUG = false;

    /**
     * Supported RepositoryObject schema IDs for backup/restore.
     */
    @NonNull
    private static final List<UUID> SUPPORTED_SCHEMA_IDS = Arrays.asList(
            SpaceSettings.SCHEMA_ID,
            Space.SCHEMA_ID,
            Profile.SCHEMA_ID,
            Contact.SCHEMA_ID,
            CallReceiver.SCHEMA_ID,
            Group.SCHEMA_ID
    );

    public static final String ACTION_START_BACKUP = "org.twinlife.device.android.twinme.START_BACKUP";
    public static final String ACTION_CANCEL_BACKUP = "org.twinlife.device.android.twinme.CANCEL_BACKUP";
    public static final String ACTION_STOP = "org.twinlife.device.android.twinme.STOP";
    public static final String ACTION_START_RESTORE = "org.twinlife.device.android.twinme.START_RESTORE";
    public static final String ACTION_COMMIT_RESTORE = "org.twinlife.device.android.twinme.COMMIT_RESTORE";
    public static final String ACTION_CANCEL_RESTORE = "org.twinlife.device.android.twinme.CANCEL_RESTORE";
    public static final String ACTION_GENERATE_WORDS = "org.twinlife.device.android.twinme.GENERATE_BACKUP_WORDS";
    public static final String ACTION_VERIFY_BACKUP = "org.twinlife.device.android.twinme.VERIFY_BACKUP";
    public static final String ACTION_CHECK_FILE_SIGNATURE = "org.twinlife.device.android.twinme.CHECK_FILE_SIGNATURE";
    /**
     * Type: byte array.
     */
    public static final String PARAM_PASSWORD = "org.twinlife.device.android.twinme.PASSWORD";

    /**
     * Type: String.
     */
    public static final String PARAM_FILE_PATH = "org.twinlife.device.android.twinme.FILE_PATH";

    /**
     * Type: Boolean. Optional, if absent the backup will be in-place if the current account is the same as the one in the backup.
     */
    public static final String PARAM_IN_PLACE = "org.twinlife.device.android.twinme.IN_PLACE";

    public static final String MESSAGE_BACKUP_STATE = "backupState";
    public static final String MESSAGE_BACKUP_ERROR = "backupError";
    public static final String MESSAGE_RESTORE_HEADER_INFO = "restoreHeaderInfo";
    public static final String MESSAGE_RESTORE_STATE = "restoreState";
    public static final String MESSAGE_RESTORE_ERROR = "restoreError";
    public static final String MESSAGE_WORDS_GENERATED = "wordsGenerated";
    public static final String MESSAGE_VERIFY_REPORT = "verifyReport";
    public static final String MESSAGE_CHECK_FILE_SIGNATURE_RESULT = "checkFileSignatureResult";

    public static final String BACKUP_SERVICE_EVENT = "event";
    public static final String BACKUP_SERVICE_BACKUP_ID = "backupId";
    public static final String BACKUP_SERVICE_FILE_PATH = "backupFilePath";
    public static final String BACKUP_SERVICE_FILE_NAME = "backupFileName";
    public static final String BACKUP_SERVICE_ERROR_CODE = "errorCode";
    public static final String BACKUP_SERVICE_BASE_ERROR_CODE = "baseErrorCode";
    public static final String BACKUP_SERVICE_BACKUP_STATE = "backupState";
    public static final String BACKUP_SERVICE_RESTORE_STATE = "restoreState";
    public static final String BACKUP_SERVICE_TERMINATE_REASON = "terminateReason";
    public static final String BACKUP_SERVICE_HEADER_INFO = "headerInfo";
    public static final String BACKUP_SERVICE_LAST_BACKUP_ID = "lastBackupId";
    public static final String BACKUP_SERVICE_LAST_BACKUP_TIMESTAMP = "lastBackupTimestamp";
    public static final String BACKUP_SERVICE_PASSWORD_WORDS = "passwordWords";
    public static final String BACKUP_SERVICE_STATS = "stats";
    public static final String BACKUP_SERVICE_RESTORE_REPORT = "restoreReport";
    public static final String BACKUP_SERVICE_CHECK_FILE_SIGNATURE_RESULT = "checkFileSignatureResult";
    public static final String BACKUP_SERVICE_SYNC_ERRORS = "syncErrors";

    public static class RestoreReport implements Serializable {
        @NonNull
        public final RestoreContent.Stats contacts;
        @NonNull
        public final RestoreContent.Stats groups;
        @NonNull
        public final RestoreContent.Stats profiles;
        @NonNull
        public final RestoreContent.Stats clickToCall;

        private RestoreReport(@NonNull RestoreContent restoreContent) {
            this.contacts = restoreContent.getStats(Contact.SCHEMA_ID);
            this.groups = restoreContent.getStats(Group.SCHEMA_ID);
            this.profiles = restoreContent.getStats(Profile.SCHEMA_ID);
            this.clickToCall = restoreContent.getStats(CallReceiver.SCHEMA_ID);
        }

        private RestoreReport(@NonNull VerifyReport verifyReport) {
            this.contacts = verifyReport.getStats(Contact.SCHEMA_ID);
            this.groups = verifyReport.getStats(Group.SCHEMA_ID);
            this.profiles = verifyReport.getStats(Profile.SCHEMA_ID);
            this.clickToCall = verifyReport.getStats(CallReceiver.SCHEMA_ID);
        }

        public boolean isRestoreUpToDate() {

            return contacts.isStatsUpToDate() && profiles.isStatsUpToDate() && clickToCall.isStatsUpToDate() && groups.isStatsUpToDate();
        }
    }

    public static class SyncErrors implements Serializable {
        /**
         * TwincodeOutbound IDs which couldn't be deleted from the server.
         */
        @NonNull
        public final ArrayList<UUID> serverTwincodes = new ArrayList<>();

        /**
         * Local profiles which couldn't be synced to the server.
         */
        @NonNull
        public final ArrayList<UUID> profileSyncs = new ArrayList<>();

        /**
         * Local contacts which couldn't be synced to the server.
         */
        @NonNull
        public final ArrayList<UUID> contactSyncs = new ArrayList<>();

        /**
         * Local call receivers which couldn't be synced to the server.
         */
        @NonNull
        public final ArrayList<UUID> callReceiverSyncs = new ArrayList<>();

        /**
         * Local profiles which couldn't be deleted from the local DB.
         */
        @NonNull
        public final ArrayList<UUID> profileDeletes = new ArrayList<>();

        /**
         * Local contacts which couldn't be deleted from the local DB.
         */
        @NonNull
        public final ArrayList<UUID> contactDeletes = new ArrayList<>();

        /**
         * Local call receivers which couldn't be deleted from the local DB.
         */
        @NonNull
        public final ArrayList<UUID> callReceiverDeletes = new ArrayList<>();

        private SyncErrors(@Nullable List<UUID> serverErrors, @Nullable List<RepositoryObject> localSyncErrors, @Nullable List<RepositoryObject> localDeleteErrors) {
            if (serverErrors != null) {
                serverTwincodes.addAll(serverErrors);
            }

            if (localSyncErrors != null) {
                for (RepositoryObject object : localSyncErrors) {
                    if (object instanceof Contact) {
                        contactSyncs.add(object.getId());
                    } else if (object instanceof Profile) {
                        profileSyncs.add(object.getId());
                    } else if (object instanceof CallReceiver) {
                        callReceiverSyncs.add(object.getId());
                    }
                }
            }

            if (localDeleteErrors != null) {
                for (RepositoryObject object : localDeleteErrors) {
                    if (object instanceof Contact) {
                        contactDeletes.add(object.getId());
                    } else if (object instanceof Profile) {
                        profileDeletes.add(object.getId());
                    } else if (object instanceof CallReceiver) {
                        callReceiverDeletes.add(object.getId());
                    }
                }
            }
        }
    }
    private class TwinmeContextObserver extends TwinmeContext.DefaultObserver {

        @Override
        public void onTwinlifeReady() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeReady");
            }

            BackupService.this.onTwinlifeReady();
        }

    }

    public class LocalBinder extends Binder {
        @NonNull
        public BackupService getService() {
            return BackupService.this;
        }
    }

    @NonNull
    private final BackupServiceObserver mBackupServiceObserver = new BackupServiceObserver();

    @NonNull
    private final TwinmeContextObserver mTwinmeContextObserver = new TwinmeContextObserver();

    @Nullable
    private TwinmeContext mTwinmeContext;

    @Nullable
    private NotificationCenter mNotificationCenter;

    private int mNotificationId = -1;

    @Nullable
    private org.twinlife.twinlife.BackupService mBackupService;

    @Nullable
    private MnemonicCodeUtils mMnemonicCodeUtils;

    @Nullable
    private JobService.ProcessingLock mProcessingLock;
    @Nullable
    private JobService.NetworkLock mNetworkLock;
    private boolean mIsTwinlifeReady;

    @Nullable
    private UUID mBackupId;

    @Nullable
    private String mBackupFilePath;

    @Nullable
    private ArrayList<String> mPasswordWords;

    @Nullable
    private org.twinlife.twinlife.BackupService.ErrorCode mBackupErrorCode;

    @Nullable
    private BaseService.ErrorCode mBaseErrorCode;

    @Nullable
    private BackupHeaderInfo mBackupHeaderInfo;

    @Nullable
    private UUID mLastBackupId;

    private long mLastBackupTimestamp;

    @Nullable
    private BackupState mBackupState;

    @Nullable
    private RestoreState mRestoreState;

    @Nullable
    private org.twinlife.twinlife.BackupService.TerminateReason mTerminateReason;

    @NonNull
    private final Map<UUID, Integer> mStats = new HashMap<>();

    @Nullable
    private RestoreReport mRestoreReport;

    @Nullable
    private List<UUID> mServerTwincodeDeleteErrors;
    @Nullable
    private List<RepositoryObject> mLocalObjectSyncErrors;
    @Nullable
    private List<RepositoryObject> mLocalObjectDeleteErrors;

    @NonNull
    private final IBinder mBinder = new LocalBinder();

    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreate");
        }

        TwinmeApplicationImpl twinmeApplication = TwinmeApplicationImpl.getInstance(this);
        if (twinmeApplication != null) {
            mTwinmeContext = twinmeApplication.getTwinmeContext();

            if (mTwinmeContext != null) {
                mTwinmeContext.setObserver(mTwinmeContextObserver);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStartCommand: intent=" + intent + " flags=" + flags + " startId=" + startId);
        }

        if (intent == null || intent.getAction() == null) {

            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        switch (action) {
            case ACTION_START_BACKUP:
                onActionStartBackup(intent);
                break;
            case ACTION_CANCEL_BACKUP:
                onActionCancelBackup(intent);
                break;
            case ACTION_STOP:
                onActionStop(intent);
                break;
            case ACTION_START_RESTORE:
                onActionStartRestore(intent);
                break;
            case ACTION_COMMIT_RESTORE:
                onActionCommitRestore(intent);
                break;
            case ACTION_CANCEL_RESTORE:
                onActionCancelRestore(intent);
                break;
            case ACTION_GENERATE_WORDS:
                onActionGenerateWords(intent);
                break;
            case ACTION_VERIFY_BACKUP:
                onActionVerifyBackup(intent);
                break;
            case ACTION_CHECK_FILE_SIGNATURE:
                onActionCheckFileSignature(intent);
                break;
            default:
                Log.w(LOG_TAG, "Ignoring intent with unknown action " + action);
        }

        return START_NOT_STICKY;
    }

    private void onActionStartBackup(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStartBackup: intent=" + intent);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            onBackupError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            onBackupError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        byte[] password = intent.getByteArrayExtra(PARAM_PASSWORD);

        if (password == null && mPasswordWords == null) {
            //No explicit password, and no password generated beforehand => abort.
            onBackupError(org.twinlife.twinlife.BackupService.ErrorCode.INVALID_KEY, BaseService.ErrorCode.ENCRYPT_ERROR);
            return;
        }

        if (password != null) {
            // Keep track of the actual password used to create the backup
            mPasswordWords = getMnemonicCodeUtils().toMnemonic(password);
        }

        if (mPasswordWords == null) {
            onBackupError(org.twinlife.twinlife.BackupService.ErrorCode.KEY_GEN_FAILED, BaseService.ErrorCode.ENCRYPT_ERROR);
            return;
        }

        byte[] passwordBytes = getMnemonicCodeUtils().toEntropy(mPasswordWords);

        if (passwordBytes == null) {
            onBackupError(org.twinlife.twinlife.BackupService.ErrorCode.KEY_GEN_FAILED, BaseService.ErrorCode.ENCRYPT_ERROR);
            return;
        }

        mTwinmeContext.execute(() -> {
            startSelf();
            mBackupService.backup(passwordBytes, SUPPORTED_SCHEMA_IDS);
        });
    }

    private void onActionCancelBackup(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCancelBackup: intent=" + intent);
        }

        mBackupState = BackupState.CANCELED;
        mBackupId = null;

        if (mBackupFilePath != null) {
            File backupFile = new File(mBackupFilePath);
            if (backupFile.exists()) {
                if (!backupFile.delete()) {
                    Log.e(LOG_TAG, "Couldn't delete backup file " + backupFile);
                }
            }
        }

        mBackupFilePath = null;
        mStats.clear();
    }

    private void onActionStop(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionTerminate: intent=" + intent);
        }

        finish();
    }

    private void onActionStartRestore(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStartRestore: intent=" + intent);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        byte[] password = intent.getByteArrayExtra(PARAM_PASSWORD);
        String filePath = intent.getStringExtra(PARAM_FILE_PATH);

        Boolean inPlace;

        if (intent.hasExtra(PARAM_IN_PLACE)) {
            inPlace = intent.getBooleanExtra(PARAM_IN_PLACE, false);
        } else {
            inPlace = null;
        }

        if (password == null) {
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.KEY_GEN_FAILED, BaseService.ErrorCode.DECRYPT_ERROR);
            return;
        }

        if (filePath == null) {
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INVALID_FILE, BaseService.ErrorCode.FILE_NOT_FOUND);
            return;
        }

        mTwinmeContext.execute(() -> {
            startSelf();
            mBackupService.restore(password, filePath, SUPPORTED_SCHEMA_IDS, inPlace, mTwinmeContext);
        });
    }

    private void onActionCommitRestore(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCommitRestore: intent=" + intent);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        mTwinmeContext.execute(() -> mBackupService.commitRestore());
    }

    private void onActionCancelRestore(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCancelRestore: intent=" + intent);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        mTwinmeContext.execute(() -> mBackupService.cancelRestore());
    }

    private void onActionGenerateWords(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionGenerateWords: intent=" + intent);
        }

        byte[] password = new byte[16];

        new SecureRandom().nextBytes(password);

        mPasswordWords = getMnemonicCodeUtils().toMnemonic(password);

        if (mPasswordWords == null) {
            onBackupError(org.twinlife.twinlife.BackupService.ErrorCode.KEY_GEN_FAILED, BaseService.ErrorCode.ENCRYPT_ERROR);
            return;
        }

        sendMessage(MESSAGE_WORDS_GENERATED);
    }

    public void deleteBackups(@NonNull Consumer<Void> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteBackups: consumer=" + consumer);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            consumer.onGet(BaseService.ErrorCode.LIBRARY_ERROR, null);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            consumer.onGet(BaseService.ErrorCode.LIBRARY_ERROR, null);
            return;
        }

        mTwinmeContext.execute(() -> mBackupService.deleteBackups(consumer));
    }

    public void getAllBackups(@NonNull Consumer<List<BackupInfo>> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAllBackups: consumer=" + consumer);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            consumer.onGet(BaseService.ErrorCode.LIBRARY_ERROR, Collections.emptyList());
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            consumer.onGet(BaseService.ErrorCode.LIBRARY_ERROR, Collections.emptyList());
            return;
        }

        mTwinmeContext.execute(() -> mBackupService.getAllBackups(consumer));
    }

    private void onActionVerifyBackup(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionVerifyBackup: intent=" + intent);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INTERNAL_ERROR, BaseService.ErrorCode.LIBRARY_ERROR);
            return;
        }


        byte[] password = intent.getByteArrayExtra(PARAM_PASSWORD);
        String filePath = intent.getStringExtra(PARAM_FILE_PATH);

        if (password == null) {
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.KEY_GEN_FAILED, BaseService.ErrorCode.DECRYPT_ERROR);
            return;
        }

        if (filePath == null) {
            onRestoreError(org.twinlife.twinlife.BackupService.ErrorCode.INVALID_FILE, BaseService.ErrorCode.FILE_NOT_FOUND);
            return;
        }

        mTwinmeContext.execute(() -> {
            startSelf();
            mBackupService.verifyBackup(password, filePath, SUPPORTED_SCHEMA_IDS);
        });
    }

    public void onActionCheckFileSignature(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCheckFileSignature: intent=" + intent);
        }

        if (mBackupService == null) {
            Log.e(LOG_TAG, "mBackupService is null");
            sendCheckFileSignatureResult(false);
            return;
        }

        if (mTwinmeContext == null) {
            Log.e(LOG_TAG, "mTwinmeContext is null");
            sendCheckFileSignatureResult(false);
            return;
        }

        String filePath = intent.getStringExtra(PARAM_FILE_PATH);

        if (filePath == null) {
            Log.e(LOG_TAG, "Missing param PARAM_FILE_PATH");
            sendCheckFileSignatureResult(false);
            return;
        }

        mTwinmeContext.execute(() -> {
            boolean result = mBackupService.checkFileSignature(filePath);
            sendCheckFileSignatureResult(result);
        });
    }

    private synchronized MnemonicCodeUtils getMnemonicCodeUtils() {
        if (mMnemonicCodeUtils == null) {
            mMnemonicCodeUtils = new MnemonicCodeUtils(this);
        }
        return mMnemonicCodeUtils;
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBind");
        }

        return mBinder;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDestroy");
        }

        if (mIsTwinlifeReady && mBackupService != null) {
            mBackupService.removeServiceObserver(mBackupServiceObserver);
        }

        if (mTwinmeContext != null) {
            mTwinmeContext.removeObserver(mTwinmeContextObserver);
        }

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationCenter != null && mNotificationId > 0) {
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

    private void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        if (mTwinmeContext == null) {
            return;
        }

        mIsTwinlifeReady = true;
        mBackupService = mTwinmeContext.getBackupService();
        mBackupService.addServiceObserver(mBackupServiceObserver);
    }

    private void onTerminateBackup(@NonNull UUID backupId, @Nullable String backupFilePath, @NonNull Map<UUID, Integer> stats, boolean done) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminateBackup: backupId=" + backupId + " backupFilePath=" + backupFilePath + " done=" + done);
        }

        mBackupState = BackupState.TERMINATED;
        mBackupId = backupId;
        mBackupFilePath = backupFilePath;

        mStats.putAll(stats);

        sendMessage(MESSAGE_BACKUP_STATE);

        finish();
    }

    private void onTerminateRestore(@NonNull org.twinlife.twinlife.BackupService.TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminateRestore: terminateReason=" + terminateReason);
        }

        mRestoreState = RestoreState.TERMINATED;
        mTerminateReason = terminateReason;
        sendMessage(MESSAGE_RESTORE_STATE);

        finish();
    }

    private void onBackupError(@NonNull org.twinlife.twinlife.BackupService.ErrorCode backupErrorCode, BaseService.ErrorCode baseErrorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBackupError: backupErrorCode=" + backupErrorCode + " baseErrorCode=" + baseErrorCode);
        }

        mBackupErrorCode = backupErrorCode;
        mBaseErrorCode = baseErrorCode;
        sendMessage(MESSAGE_BACKUP_ERROR);
    }

    private void onRestoreError(@NonNull org.twinlife.twinlife.BackupService.ErrorCode backupErrorCode, @Nullable BaseService.ErrorCode baseErrorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRestoreError: errorCode=" + backupErrorCode + " parameter=" + baseErrorCode);
        }

        mBackupErrorCode = backupErrorCode;
        mBaseErrorCode = baseErrorCode;
        sendMessage(MESSAGE_RESTORE_ERROR);
    }

    private void onBackupStateChange(@NonNull UUID backupId, BackupState state) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBackupStateChange: backupId=" + backupId + " state=" + state);
        }

        mBackupState = state;

        sendMessage(MESSAGE_BACKUP_STATE);
    }

    private void onRestoreStateChange(@NonNull RestoreState state, @Nullable RestoreContent restoreContent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRestoreStateChange: state=" + state + " restoreContent=" + restoreContent);
        }

        mRestoreState = state;
        if (restoreContent != null) {
            mRestoreReport = new RestoreReport(restoreContent);
        }

        sendMessage(MESSAGE_RESTORE_STATE);
    }

    private void onHeaderInfo(@NonNull BackupHeaderInfo backupHeaderInfo, @Nullable UUID lastBackupId, long lastBackupTimestamp) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onHeaderInfo: backupHeaderInfo=" + backupHeaderInfo + " lastBackupId=" + lastBackupId + " lastBackupTimestamp=" + lastBackupTimestamp);
        }

        mBackupHeaderInfo = backupHeaderInfo;
        mLastBackupId = lastBackupId;
        mLastBackupTimestamp = lastBackupTimestamp;
        sendMessage(MESSAGE_RESTORE_HEADER_INFO);
    }

    private void onVerifyReport(@NonNull VerifyReport report) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onVerifyReport: report=" + report);
        }

        mRestoreReport = new RestoreReport(report);

        sendMessage(MESSAGE_VERIFY_REPORT);

        finish();
    }

    private void onSyncError(@NonNull List<UUID> serverTwincodeDeleteErrors, @NonNull List<RepositoryObject> localObjectSyncErrors, @NonNull List<RepositoryObject> localObjectDeleteErrors) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSyncError: serverTwincodeDeleteErrors=[" + Arrays.asList(serverTwincodeDeleteErrors.toArray()) + " localObjectSyncErrors=" + Arrays.asList(localObjectSyncErrors.toArray()) + " localObjectDeleteErrors=" + Arrays.asList(localObjectDeleteErrors.toArray()));
        }

        mBackupErrorCode = org.twinlife.twinlife.BackupService.ErrorCode.SYNC_FAILED;

        mServerTwincodeDeleteErrors = serverTwincodeDeleteErrors;
        mLocalObjectSyncErrors = localObjectSyncErrors;
        mLocalObjectDeleteErrors = localObjectDeleteErrors;

        sendMessage(MESSAGE_RESTORE_ERROR);
    }


    private void sendMessage(@NonNull String event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendMessage: event=" + event);
        }

        Intent intent = new Intent(Intents.INTENT_BACKUP_SERVICE_MESSAGE);
        intent.setPackage(getPackageName());
        intent.putExtra(BACKUP_SERVICE_EVENT, event);

        intent.putExtra(BACKUP_SERVICE_BACKUP_ID, mBackupId);
        intent.putExtra(BACKUP_SERVICE_FILE_PATH, mBackupFilePath);
        intent.putExtra(BACKUP_SERVICE_ERROR_CODE, mBackupErrorCode);
        intent.putExtra(BACKUP_SERVICE_BASE_ERROR_CODE, mBaseErrorCode);
        intent.putExtra(BACKUP_SERVICE_BACKUP_STATE, mBackupState);
        intent.putExtra(BACKUP_SERVICE_RESTORE_STATE, mRestoreState);
        intent.putExtra(BACKUP_SERVICE_TERMINATE_REASON, mTerminateReason);
        intent.putExtra(BACKUP_SERVICE_HEADER_INFO, mBackupHeaderInfo);
        intent.putExtra(BACKUP_SERVICE_LAST_BACKUP_ID, mLastBackupId);
        intent.putExtra(BACKUP_SERVICE_LAST_BACKUP_TIMESTAMP, mLastBackupTimestamp);
        intent.putStringArrayListExtra(BACKUP_SERVICE_PASSWORD_WORDS, mPasswordWords);
        intent.putExtra(BACKUP_SERVICE_STATS, new BackupStats(mStats));
        if (mRestoreReport != null) {
            intent.putExtra(BACKUP_SERVICE_RESTORE_REPORT, mRestoreReport);
        }

        if (mBackupErrorCode == org.twinlife.twinlife.BackupService.ErrorCode.SYNC_FAILED) {
            intent.putExtra(BACKUP_SERVICE_SYNC_ERRORS, new SyncErrors(mServerTwincodeDeleteErrors, mLocalObjectSyncErrors, mLocalObjectDeleteErrors));
        }

        sendBroadcast(intent);
    }

    private void sendCheckFileSignatureResult(boolean result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendCheckFileSignatureResult: result=" + result);
        }

        Intent intent = new Intent(Intents.INTENT_BACKUP_SERVICE_MESSAGE);
        intent.setPackage(getPackageName());
        intent.putExtra(BACKUP_SERVICE_EVENT, MESSAGE_CHECK_FILE_SIGNATURE_RESULT);
        intent.putExtra(BACKUP_SERVICE_CHECK_FILE_SIGNATURE_RESULT, result);

        sendBroadcast(intent);
    }

    private void startSelf() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startSelf");
        }

        TwinmeApplicationImpl twinmeApplication = TwinmeApplicationImpl.getInstance(this);
        if (twinmeApplication != null && mTwinmeContext != null) {

            // Get the power processing lock to tell the system we need the CPU.
            mProcessingLock = twinmeApplication.allocateProcessingLock();

            // We also need the network for the lifetime of this service.
            mNetworkLock = twinmeApplication.allocateNetworkLock();

            mNotificationCenter = mTwinmeContext.getNotificationCenter();
            mNotificationId = mNotificationCenter.startBackupService(this, 0);
        }
    }

    private void finish() {
        if (DEBUG) {
            Log.d(LOG_TAG, "finish");
        }

        stopForeground(true);

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationCenter != null && mNotificationId > 0) {
            mNotificationCenter.cancel(mNotificationId);
        }
        stopSelf();
    }

    private class BackupServiceObserver implements org.twinlife.twinlife.BackupService.ServiceObserver {
        @Override
        public void onHeaderInfo(@NonNull BackupHeaderInfo backupHeaderInfo, @Nullable UUID lastBackupId, long lastBackupTimestamp) {
            BackupService.this.onHeaderInfo(backupHeaderInfo, lastBackupId, lastBackupTimestamp);
        }

        @Override
        public void onRestoreStateChange(@NonNull RestoreState state, @Nullable RestoreContent restoreContent) {
            BackupService.this.onRestoreStateChange(state, restoreContent);
        }

        @Override
        public void onBackupStateChange(@NonNull UUID backupId, @NonNull BackupState state) {
            BackupService.this.onBackupStateChange(backupId, state);
        }

        @Override
        public void onBackupError(@NonNull org.twinlife.twinlife.BackupService.ErrorCode backupErrorCode, @NonNull BaseService.ErrorCode baseErrorCode) {
            BackupService.this.onBackupError(backupErrorCode, baseErrorCode);
        }

        @Override
        public void onRestoreError(@NonNull org.twinlife.twinlife.BackupService.ErrorCode backupErrorCode, @NonNull BaseService.ErrorCode baseErrorCode) {
            BackupService.this.onRestoreError(backupErrorCode, baseErrorCode);
        }

        @Override
        public void onTerminateBackup(@NonNull UUID backupId, @Nullable String backupFilePath, @NonNull Map<UUID, Integer> stats, boolean done) {
            BackupService.this.onTerminateBackup(backupId, backupFilePath, stats, done);
        }

        @Override
        public void onTerminateRestore(@NonNull org.twinlife.twinlife.BackupService.TerminateReason terminateReason) {
            BackupService.this.onTerminateRestore(terminateReason);
        }

        @Override
        public void onVerifyReport(@NonNull VerifyReport report) {
            BackupService.this.onVerifyReport(report);
        }

        @Override
        public void onSyncError(@NonNull List<UUID> serverTwincodeDeleteErrors, @NonNull List<RepositoryObject> localObjectSyncErrors, @NonNull List<RepositoryObject> localObjectDeleteErrors) {
            BackupService.this.onSyncError(serverTwincodeDeleteErrors, localObjectSyncErrors, localObjectDeleteErrors);
        }
    }
}
