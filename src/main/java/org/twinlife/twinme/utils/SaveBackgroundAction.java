/*
 *  Copyright (c) 2020-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinme.actions.BackgroundAction;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;

/**
 * Save a file asynchronously to avoid blocking the UI thread.
 * <p>
 * The SaveBackgroundAction can save only one file at a time.
 * - if the operation succeeds, report the toast("Saved!")
 * - if the operation failed, report the onError() with storage error.
 */
public class SaveBackgroundAction extends BackgroundAction {
    private static final String LOG_TAG = "SaveBackgroundTask";
    private static final boolean DEBUG = false;

    private final WeakReference<TwinmeActivity> mActivityWeakReference;
    private final ContentResolver mContentResolver;
    private final FileInfo mMedia;
    private final File mFile;
    private final Uri mUri;
    @StringRes
    private final int mMessageId;
    private ErrorCode mResult;

    public SaveBackgroundAction(@NonNull TwinmeActivity activity, @NonNull File file, @NonNull Uri uri, @StringRes int messageId) {
        super(activity.getTwinmeContext(), BackgroundAction.DEFAULT_TIMEOUT);

        mActivityWeakReference = new WeakReference<>(activity);
        mContentResolver = activity.getContentResolver();
        mFile = file;
        mUri = uri;
        mMedia = new FileInfo(activity.getApplicationContext(), Uri.fromFile(mFile));
        mResult = ErrorCode.LIBRARY_ERROR;
        mMessageId = messageId;
    }

    public SaveBackgroundAction(@NonNull TwinmeActivity activity, @NonNull File file, @NonNull File target, @StringRes int messageId) {
        super(activity.getTwinmeContext(), BackgroundAction.DEFAULT_TIMEOUT);

        mActivityWeakReference = new WeakReference<>(activity);
        mContentResolver = activity.getContentResolver();
        mFile = file;
        mUri = Uri.fromFile(target);
        mMedia = new FileInfo(activity.getApplicationContext(), Uri.fromFile(mFile));
        mResult = ErrorCode.LIBRARY_ERROR;
        mMessageId = messageId;
    }

    protected void execute() {
        if (DEBUG) {
            Log.d(LOG_TAG, "execute");
        }

        try {
            Uri target;
            if (mMedia.isImage() || mMedia.isVideo()) {
                ContentValues values = new ContentValues();
                Uri base;
                if (mMedia.isVideo()) {
                    base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else {
                    base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                values.put(MediaStore.MediaColumns.TITLE, mMedia.getFilename());
                values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
                values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mMedia.getMimeType());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    if (mMedia.isVideo()) {
                        base = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    } else {
                        base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    }
                }

                target = mContentResolver.insert(base, values);
            } else {
                target = mUri;
            }

            if (target != null) {
                try (ParcelFileDescriptor parcelFileDescriptor = mContentResolver.openFileDescriptor(target, "w")) {
                    if (parcelFileDescriptor != null) {

                        try (InputStream inputStream = new FileInputStream(mFile);
                             FileOutputStream outputStream = new FileOutputStream(parcelFileDescriptor.getFileDescriptor())) {
                            if (Utils.copyStream(inputStream, outputStream)) {
                                mResult = ErrorCode.SUCCESS;

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (mMedia.isVideo() || mMedia.isImage())) {
                                    ContentValues values = new ContentValues();
                                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                                    mContentResolver.update(target, values, null, null);
                                }
                            } else {
                                mResult = ErrorCode.NO_STORAGE_SPACE;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (Logger.ERROR) {
                Log.e(LOG_TAG, "Exception", e);
            }
        }

        final TwinmeActivity activity = mActivityWeakReference.get();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (mResult == ErrorCode.SUCCESS) {
                    Toast.makeText(activity.getApplicationContext(), mMessageId, Toast.LENGTH_SHORT).show();
                } else {
                    activity.onExecutionError(mResult);
                }
            });
        }
        EventMonitor.event("SaveBackgroundTask", mStartTime);
    }
}
