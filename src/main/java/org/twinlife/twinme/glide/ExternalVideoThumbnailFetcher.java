/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import org.twinlife.twinme.utils.FileInfo;

import java.io.IOException;

/**
 * Load the thumbnail from an external video (i.e. a video coming from the OS' media picker).
 */
public class ExternalVideoThumbnailFetcher implements DataFetcher<Bitmap> {
    private static final String LOG_TAG = "MediaInfoFetcher";
    private static final boolean DEBUG = false;

    private static final boolean USE_MODERN_THUMBNAIL_LOADER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;

    @NonNull
    private final FileInfo mFileInfo;
    @NonNull
    private final Context mContext;
    private final int mWidth;
    private final int mHeight;
    @Nullable
    private final CancellationSignal mCancellationSignal;

    ExternalVideoThumbnailFetcher(@NonNull FileInfo imageInfo, @NonNull Context context, int width, int height) {
        if (DEBUG) {
            Log.d(LOG_TAG, "ExternalVideoThumbnailFetcher: imageInfo=" + imageInfo + " context=" + context + " width=" + width + " height=" + height);
        }

        mFileInfo = imageInfo;
        mContext = context;
        mWidth = width;
        mHeight = height;
        mCancellationSignal = USE_MODERN_THUMBNAIL_LOADER ? new CancellationSignal() : null;
    }

    @Override
    public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Bitmap> callback) {
        if (DEBUG) {
            Log.d(LOG_TAG, "loadData: priority=" + priority + " callback=" + callback);
        }

        if (USE_MODERN_THUMBNAIL_LOADER) {
            try {
                Bitmap bitmap = mContext.getContentResolver().loadThumbnail(mFileInfo.getUri(), new Size(mWidth, mHeight), mCancellationSignal);

                callback.onDataReady(bitmap);
            } catch (IOException e) {
                // For some videos (e.g. screen recordings from a Poco F3), ContentResolver can't create
                // a thumbnail (DecodeException) but ThumbnailUtils can.
                legacyThumbnailExtract(callback);
            }
        } else {
            legacyThumbnailExtract(callback);
        }
    }

    private void legacyThumbnailExtract(@NonNull DataCallback<? super Bitmap> callback) {
        String path = mFileInfo.getPath() != null ? mFileInfo.getPath() : mFileInfo.getUri().getPath();

        if (path == null) {
            callback.onLoadFailed(new Exception("No path found for video: " + mFileInfo));
            return;
        }

        Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);

        if (bitmap != null) {
            callback.onDataReady(bitmap);
        } else {
            callback.onLoadFailed(new Exception("ThumbnailUtils.createVideoThumbnail() failed for video: " + mFileInfo));
        }
    }

    @Override
    public void cleanup() {
        //NOOP
    }

    @Override
    public void cancel() {
        if (DEBUG) {
            Log.d(LOG_TAG, "cancel");
        }

        if (mCancellationSignal != null) {
            mCancellationSignal.cancel();
        }
    }

    @NonNull
    @Override
    public Class<Bitmap> getDataClass() {

        return Bitmap.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {

        return DataSource.LOCAL;
    }
}
