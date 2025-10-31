/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.glide;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.data.DataFetcher;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;

import java.util.concurrent.ExecutorService;

/**
 * Support to load an image stored in the SQLcipher database managed by the ImageService.
 */
public class TwinlifeImageFetcher implements DataFetcher<Bitmap> {
    private static final String LOG_TAG = "TwinlifeImageFetcher";
    private static final boolean DEBUG = false;

    @NonNull
    private final ImageService mImageService;
    private final ImageId mImageId;
    private final ImageService.Kind mKind;

    TwinlifeImageFetcher(@NonNull ImageService imageService, @NonNull ImageId imageId, boolean thumbnail) {

        mImageService = imageService;
        mImageId = imageId;
        mKind = thumbnail ? ImageService.Kind.THUMBNAIL : ImageService.Kind.NORMAL;
    }

    /**
     * Fetch data from which a resource can be decoded.
     *
     * <p>This will always be called on background thread so it is safe to perform long running tasks
     * here. Any third party libraries called must be thread safe (or move the work to another thread)
     * since this method will be called from a thread in a {@link
     * ExecutorService} that may have more than one background thread. You
     * <b>MUST</b> use the {@link DataCallback} once the request is complete.
     *
     * <p>You are free to move the fetch work to another thread and call the callback from there.
     *
     * <p>This method will only be called when the corresponding resource is not in the cache.
     *
     * <p>Note - this method will be run on a background thread so blocking I/O is safe.
     *
     * @param priority The priority with which the request should be completed.
     * @param callback The callback to use when the request is complete
     * @see #cleanup() where the data retuned will be cleaned up
     */
    @Override
    public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Bitmap> callback) {
        if (DEBUG) {
            Log.d(LOG_TAG, "loadData " + priority + " " + mImageId + "." + mKind);
        }

        mImageService.getImageFromServer(mImageId, mKind, (ErrorCode errorCode, Bitmap image) -> {

            if (errorCode == ErrorCode.SUCCESS && image != null) {
                callback.onDataReady(image);
            } else {
                callback.onLoadFailed(new Exception("Error " + errorCode));
            }
        });
    }

    /**
     * Cleanup or recycle any resources used by this data fetcher. This method will be called in a
     * finally block after the data provided by {@link #loadData(Priority,
     * DataCallback)} has been decoded by the {@link
     * ResourceDecoder}.
     *
     * <p>Note - this method will be run on a background thread so blocking I/O is safe.
     */
    @Override
    public void cleanup() {

    }

    /**
     * A method that will be called when a load is no longer relevant and has been cancelled. This
     * method does not need to guarantee that any in process loads do not finish. It also may be
     * called before a load starts or after it finishes.
     *
     * <p>The best way to use this method is to cancel any loads that have not yet started, but allow
     * those that are in process to finish since its we typically will want to display the same
     * resource in a different view in the near future.
     *
     * <p>Note - this method will be run on the main thread so it should not perform blocking
     * operations and should finish quickly.
     */
    @Override
    public void cancel() {

    }

    /**
     * Returns the class of the data this fetcher will attempt to obtain.
     */
    @NonNull
    @Override
    public Class<Bitmap> getDataClass() {

        return Bitmap.class;
    }

    /**
     * Returns the {@link DataSource} this fetcher will return data from.
     */
    @NonNull
    @Override
    public DataSource getDataSource() {

        // Don't use LOCAL because loading from the SQLCipher database is already faster (we don't want a copy in disk cache).
        return DataSource.MEMORY_CACHE;
    }
}
