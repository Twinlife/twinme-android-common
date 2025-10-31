/*
 *  Copyright (c) 2023-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.export;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.twinlife.twinme.NotificationCenter;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinme.TwinmeApplication;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.Intents;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import net.lingala.zip4j.io.outputstream.ZipOutputStream;

/**
 * Export service.
 * <p>
 * The Export Service can run in background due to the possible long execution to produce the ZIP
 * file with exported content.  While it exports and produces the ZIP file, a notification is displayed
 * and the service can run as foreground service (hence with application in background).
 * </p>
 * - ACTION_SCAN prepares the export service to scan the space/group/contact to export,
 * - ACTION_EXPORT triggers the creation of the ZIP file,
 * - ACTION_CANCEL aborts the creation of the ZIP file,
 * - ACTION_STOP informs the service that the ExportActivity has finished.
 * <p>
 * While the service is scanning and exporting, it sends events with:
 * </p>
 * - MESSAGE_EVENT: the event name
 * - MESSAGE_STATE: the ExportState enum corresponding to the state
 * - MESSAGE_STATS: the ExportStats object with the current stats
 */
public class ExportService extends Service implements ExportObserver {
    private static final String LOG_TAG = "ExportService";
    private static final boolean DEBUG = false;

    private static final String EXPORT_ALL_SHORT_NAME = "all";
    private static final String EXPORT_FILE_SHORT_NAME = "file";
    private static final String EXPORT_MEDIA_SHORT_NAME = "media";
    private static final String EXPORT_MESSAGE_SHORT_NAME = "msg";
    private static final String EXPORT_VOICE_SHORT_NAME = "voice";

    /**
     * Actions supported by the service.
     */
    public static final String ACTION_SCAN = "org.twinlife.device.android.twinme.SCAN_EXPORT";
    public static final String ACTION_PREPARE = "org.twinlife.device.android.twinme.PREPARE_EXPORT";
    public static final String ACTION_EXPORT = "org.twinlife.device.android.twinme.RUN_EXPORT";
    public static final String ACTION_CANCEL = "org.twinlife.device.android.twinme.CANCEL_EXPORT";
    public static final String ACTION_STOP = "org.twinlife.device.android.twinme.STOP_EXPORT";

    /**
     * Action parameters.
     */
    public static final String PARAM_EXPORT_URI = "uri";
    public static final String PARAM_SPACE_ID = "spaceId";
    public static final String PARAM_GROUP_ID = "groupId";
    public static final String PARAM_CONTACT_ID = "contactId";
    public static final String PARAM_FILTER_TYPES = "filterTypes";
    // public static final String PARAM_BEFORE_DATE = "beforeDate";

    /**
     * Event message types.
     */
    public static final String MESSAGE_EVENT = "event";
    public static final String MESSAGE_STATE = "state";
    public static final String MESSAGE_PROGRESS = "progress";
    public static final String MESSAGE_ERROR = "error";
    public static final String MESSAGE_STATS = "stats";
    public static final String MESSAGE_EXPORT_NAME = "exportName";

    @Nullable
    private NotificationCenter mNotificationCenter;
    private int mNotificationId;
    private JobService.ProcessingLock mProcessingLock;
    @NonNull
    private final Set<Descriptor.Type> mFilterTypes = new HashSet<>();
    @Nullable
    private ExportExecutor mExport;
    @Nullable
    private ZipOutputStream mZipOutputstream;
    @Nullable
    private DocumentFile mZipFile;
    @NonNull
    private ExportState mState = ExportState.EXPORT_READY;
    @NonNull
    private ExportStats mStats = new ExportStats();
    private long mLastMessage;
    private int mLastProgress;

    @NonNull
    private String mApplicationName = "Twinme";
    @Nullable
    private String mExportName;
    private long mExportTotalSize;

    @Nullable
    private TwinmeContext mTwinmeContext;

    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreate");
        }

        mLastMessage = 0;
        mLastProgress = 0;
        mFilterTypes.add(Descriptor.Type.OBJECT_DESCRIPTOR);
        mFilterTypes.add(Descriptor.Type.IMAGE_DESCRIPTOR);
        mFilterTypes.add(Descriptor.Type.VIDEO_DESCRIPTOR);
        mFilterTypes.add(Descriptor.Type.AUDIO_DESCRIPTOR);
        mFilterTypes.add(Descriptor.Type.NAMED_FILE_DESCRIPTOR);

        TwinmeApplication twinmeApplication = TwinmeApplicationImpl.getInstance(this);
        if (twinmeApplication != null) {
            mTwinmeContext = twinmeApplication.getTwinmeContext();
            if (mTwinmeContext != null) {
                mExport = new ExportExecutor(mTwinmeContext, this, false, false);

                mNotificationCenter = mTwinmeContext.getNotificationCenter();
            }
            mApplicationName = twinmeApplication.getApplicationName();
            // Get the power processing lock to tell the system we need the CPU.
            mProcessingLock = twinmeApplication.allocateProcessingLock();
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

        final String action = intent.getAction();
        switch (action) {
            case ACTION_SCAN:
                onActionScan(intent);
                break;

            case ACTION_PREPARE:
                onActionPrepare(intent);
                break;

            case ACTION_EXPORT:
                onActionExport(intent);
                break;

            case ACTION_CANCEL:
                onActionCancel(intent);
                break;

            case ACTION_STOP:
                onActionStop(intent);
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

        if (mExport != null) {
            mExport.dispose();
            mExport = null;
        }

        // If a zip file is opened, close it.
        if (mZipOutputstream != null) {
            try {
                mZipOutputstream.close();
                mZipOutputstream = null;
            } catch (Exception exception) {
                Log.e(LOG_TAG, "Exception", exception);
            }
        }

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationId > 0 && mNotificationCenter != null) {
            mNotificationCenter.cancel(mNotificationId);
        }

        if (mProcessingLock != null) {
            mProcessingLock.release();
            mProcessingLock = null;
        }

        super.onDestroy();
    }

    /**
     * Give information about the exporter progress.
     *
     * @param state the current export state.
     * @param stats the current stats about the export.
     */
    @Override
    public void onProgress(@NonNull ExportState state, @NonNull ExportStats stats) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onProgress state=" + state);
        }

        mStats = stats;
        mState = state;
        if (state == ExportState.EXPORT_DONE) {
            sendMessage(MESSAGE_STATE, state, stats);
            finish();

        } else if (state == ExportState.EXPORT_WAIT || state == ExportState.EXPORT_ERROR) {
            sendMessage(MESSAGE_STATE, state, stats);

        } else {
            long now = System.currentTimeMillis();

            // Report progress on the notification only when we are exporting and the progress has changed.
            if (state == ExportState.EXPORT_EXPORTING) {
                long exported = getExportSize(stats);
                int progress = (exported >= mExportTotalSize) ? 100 : (int) ((100L * exported) / mExportTotalSize);
                if (mNotificationCenter != null && progress != mLastProgress) {
                    mLastProgress = progress;
                    mNotificationId = mNotificationCenter.startExportService(this, progress);
                }
            }

            // Rate limit 4 messages/second.
            if ((now - mLastMessage) < 250) {
                return;
            }

            mLastMessage = now;
            sendMessage(MESSAGE_PROGRESS, state, stats);
        }
    }

    /**
     * Report an error raised while exporting medias.
     *
     * @param message the error message.
     */
    @Override
    public void onError(@NonNull String message) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError message=" + message);
        }

        Intent intent = new Intent(Intents.INTENT_EXPORT_SERVICE_MESSAGE);
        intent.setPackage(getPackageName());
        intent.putExtra(MESSAGE_ERROR, message);
        sendBroadcast(intent);
    }

    /**
     * Action to scan the space/group/contact to be exported.
     *
     * @param intent the intent parameters.
     */
    private void onActionScan(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionScan intent=" + intent);
        }

        if (mExport == null) {
            finish();
            return;
        }

        // If we are not in the good state, do nothing but report the current state.
        // The UI will update accordingly.
        if (mState != ExportState.EXPORT_READY) {

            sendMessage(MESSAGE_STATE, mState, mStats);
            return;
        }

        final UUID spaceId = (UUID)intent.getSerializableExtra(PARAM_SPACE_ID);
        final UUID groupId = (UUID)intent.getSerializableExtra(PARAM_GROUP_ID);
        final UUID contactId = (UUID)intent.getSerializableExtra(PARAM_CONTACT_ID);

        mExportName = null;
        mExport.setTypeFilter(mFilterTypes.toArray(new Descriptor.Type[0]));
        if (groupId != null) {
            mExport.prepareGroup(groupId);
        } else if (contactId != null) {
            mExport.prepareContact(contactId);
        } else if (spaceId != null) {
            mExport.prepareSpace(spaceId);
        } else {
            mExport.prepareAll();
        }
    }

    /**
     * Action to prepare the export of space/group/contact by indicating the contents to export
     * and build the final export name.
     *
     * @param intent the intent parameters.
     */
    private void onActionPrepare(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionPrepare intent=" + intent);
        }

        if (mExport == null || mNotificationCenter == null) {
            finish();
            return;
        }

        // If we are not in the good state, do nothing.
        if (mState != ExportState.EXPORT_WAIT) {

            sendMessage(MESSAGE_STATE, mState, mStats);
            return;
        }

        final String types = intent.getStringExtra(PARAM_FILTER_TYPES);
        if (types != null) {
            mFilterTypes.clear();
            for (String type : types.split(",")) {
                switch (type) {
                    case "message":
                        mFilterTypes.add(Descriptor.Type.OBJECT_DESCRIPTOR);
                        break;

                    case "image":
                        mFilterTypes.add(Descriptor.Type.IMAGE_DESCRIPTOR);
                        break;

                    case "audio":
                        mFilterTypes.add(Descriptor.Type.AUDIO_DESCRIPTOR);
                        break;

                    case "video":
                        mFilterTypes.add(Descriptor.Type.VIDEO_DESCRIPTOR);
                        break;

                    case "file":
                        mFilterTypes.add(Descriptor.Type.NAMED_FILE_DESCRIPTOR);
                        break;

                }
            }
        }
        mExportTotalSize = getExportSize(mStats);

        mExportName = getExportFileName();
        sendMessage(MESSAGE_PROGRESS, mState, mStats);
    }

    /**
     * Action to start the export of space/group/contact previously selected with onActionScan().
     *
     * @param intent the intent parameters.
     */
    private void onActionExport(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionExport intent=" + intent);
        }

        if (mExport == null || mNotificationCenter == null) {
            finish();
            return;
        }

        // If we are not in the good state, do nothing.
        if (mState != ExportState.EXPORT_WAIT) {

            sendMessage(MESSAGE_STATE, mState, mStats);
            return;
        }

        try {
            final Uri uri = intent.getParcelableExtra(PARAM_EXPORT_URI);
            final ContentResolver resolver = getContentResolver();
            mNotificationId = mNotificationCenter.startExportService(this, 0);
            if (uri != null) {
                final OutputStream outputStream = resolver.openOutputStream(uri, "wt");
                mZipOutputstream = new ZipOutputStream(outputStream);
                mExport.setTypeFilter(mFilterTypes.toArray(new Descriptor.Type[0]));
                mExport.runExport(mZipOutputstream);
            }
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Export failed: ", exception);

            onError("export failed: " + exception.getMessage());
        }
    }

    /**
     * Compute the actual exported size based on filters.
     * Note: messages are ignored.
     *
     * @param stats the stats reported by the ExportExecutor
     * @return the current exported size.
     */
    private long getExportSize(@NonNull ExportStats stats) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getExportSize stats=" + stats);
        }

        long total = 0;

        for (Descriptor.Type type : mFilterTypes) {
            switch (type) {
                case IMAGE_DESCRIPTOR:
                    total += stats.imageSize;
                    break;

                case AUDIO_DESCRIPTOR:
                    total += stats.audioSize;
                    break;

                case VIDEO_DESCRIPTOR:
                    total += stats.videoSize;
                    break;

                case NAMED_FILE_DESCRIPTOR:
                    total += stats.fileSize;
                    break;

                default:
                    break;
            }
        }
        return total;
    }

    /**
     * Action to cancel an export.
     *
     * @param intent the intent parameters.
     */
    private void onActionCancel(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCancel intent=" + intent);
        }

        if (mExport == null || mTwinmeContext == null) {
            finish();
            return;
        }

        mTwinmeContext.execute(() -> {
            mExport.dispose();
            mExport = null;

            // If a zip file is opened, close it.
            if (mZipOutputstream != null) {
                try {
                    mZipOutputstream.close();
                    mZipOutputstream = null;
                } catch (Exception exception) {
                    Log.e(LOG_TAG, "Could not close mZipOutputstream", exception);
                }
            }

            if (mZipFile != null) {
                mZipFile.delete();
                mZipFile = null;
            }
        });
    }

    /**
     * Action to stop the service (unless an export is in progress).
     *
     * @param intent intent the intent parameters.
     */
    private void onActionStop(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionExport intent=" + intent);
        }

        // If we are exporting, don't stop.
        if (mState != ExportState.EXPORT_EXPORTING) {
            finish();
        }
    }

    /**
     * Broadcast a message about the current export.
     *
     * @param event the event type.
     * @param state the current state.
     * @param stats the current stats.
     */
    private void sendMessage(@NonNull String event, @NonNull ExportState state, @NonNull ExportStats stats) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendMessage: event=" + event);
        }

        mState = state;
        Intent intent = new Intent(Intents.INTENT_EXPORT_SERVICE_MESSAGE);
        intent.setPackage(getPackageName());
        intent.putExtra(MESSAGE_EVENT, event);
        intent.putExtra(MESSAGE_STATE, state);
        intent.putExtra(MESSAGE_STATS, stats);
        if (mExportName != null) {
            intent.putExtra(MESSAGE_EXPORT_NAME, mExportName);
        }
        if (state == ExportState.EXPORT_EXPORTING || state == ExportState.EXPORT_DONE) {
            intent.putExtra(MESSAGE_PROGRESS, mLastProgress);
        }
        sendBroadcast(intent);
    }

    @NonNull
    private String getExportFileName() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getExportFileName");
        }

        final StringBuilder builder = new StringBuilder();
        final String prefixFileName;
        if (mExport != null) {
            final Space space = mExport.getSpace();
            final Contact contact = mExport.getContact();
            final Group group = mExport.getGroup();
            if (space != null) {
                prefixFileName = ExportExecutor.exportName(space.getSpaceSettings().getName());
            } else if (contact != null && contact.getName() != null) {
                prefixFileName = ExportExecutor.exportName(contact.getName());
            } else if (group != null && group.getName() != null) {
                prefixFileName = ExportExecutor.exportName(group.getName());
            } else {
                prefixFileName = mApplicationName;
            }
        } else {
            prefixFileName = mApplicationName;
        }

        builder.append(prefixFileName);
        builder.append("-");

        if (mFilterTypes.size() == 5) {
            builder.append(EXPORT_ALL_SHORT_NAME);
            builder.append("-");
        } else {
            for (Descriptor.Type type : mFilterTypes) {
                String shortTypeName = getContentTypeShortName(type);
                if (!shortTypeName.isEmpty() && !builder.toString().contains(shortTypeName)) {
                    builder.append(shortTypeName);
                    builder.append("-");
                }
            }
        }

        // Note YY is not supported old Android, use ISO format.
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        builder.append(simpleDateFormat.format(new Date()));
        builder.append(".zip");

        return builder.toString();
    }

    @NonNull
    private String getContentTypeShortName(@NonNull Descriptor.Type type) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContentTypeShortName type=" + type);
        }

        switch (type) {
            case OBJECT_DESCRIPTOR:
                return EXPORT_MESSAGE_SHORT_NAME;

            case IMAGE_DESCRIPTOR:
            case VIDEO_DESCRIPTOR:
                return EXPORT_MEDIA_SHORT_NAME;

            case NAMED_FILE_DESCRIPTOR:
                return EXPORT_FILE_SHORT_NAME;

            case AUDIO_DESCRIPTOR:
                return EXPORT_VOICE_SHORT_NAME;

            default:
                return "";
        }
    }

    /**
     * Finish and stop the service.
     */
    private void finish() {
        if (DEBUG) {
            Log.d(LOG_TAG, "finish");
        }

        stopForeground(true);

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        if (mNotificationId > 0 && mNotificationCenter != null) {
            mNotificationCenter.cancel(mNotificationId);
        }
        stopSelf();
    }
}
