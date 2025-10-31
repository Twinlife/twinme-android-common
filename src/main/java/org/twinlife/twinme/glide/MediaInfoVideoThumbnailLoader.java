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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import org.twinlife.twinme.utils.FileInfo;

/**
 * Generate a thumbnail using {@link ExternalVideoThumbnailFetcher}.
 */
public class MediaInfoVideoThumbnailLoader implements ModelLoader<FileInfo, Bitmap> {
    private static final String LOG_TAG = "MediaInfoVideoLoader";
    private static final boolean DEBUG = false;

    @NonNull
    private final Context mContext;

    MediaInfoVideoThumbnailLoader(@NonNull Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "MediaInfoVideoThumbnailLoader: context=" + context);
        }

        mContext = context;
    }

    @Nullable
    @Override
    public LoadData<Bitmap> buildLoadData(@NonNull FileInfo fileInfo, int width, int height, @NonNull Options options) {
        if (DEBUG) {
            Log.d(LOG_TAG, "buildLoadData: mediaInfo=" + fileInfo + " width=" + width + " height=" + height + " options=" + options);
        }

        return new LoadData<>(new ObjectKey(fileInfo.getUri() + "." + width + "." + height),
                new ExternalVideoThumbnailFetcher(fileInfo, mContext, width, height));
    }

    @Override
    public boolean handles(@NonNull FileInfo fileInfo) {

        return fileInfo.isVideo();
    }

    public static class Factory implements ModelLoaderFactory<FileInfo, Bitmap> {

        @NonNull
        public static Factory create(@NonNull Context context) {

            return new Factory(context);
        }

        @NonNull
        private final Context mContext;

        private Factory(@NonNull Context context) {

            mContext = context;
        }

        @NonNull
        @Override
        public ModelLoader<FileInfo, Bitmap> build(@NonNull MultiModelLoaderFactory multiFactory) {

            return new MediaInfoVideoThumbnailLoader(mContext);
        }

        @Override
        public void teardown() {

        }
    }
}
