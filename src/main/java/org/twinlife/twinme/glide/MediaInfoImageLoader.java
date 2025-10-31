/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.glide;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;

import org.twinlife.twinme.utils.FileInfo;

import java.io.InputStream;

/**
 * Delegate to the built-in Uri loader.
 */
public class MediaInfoImageLoader implements ModelLoader<FileInfo, InputStream> {
    private static final String LOG_TAG = "MediaInfoImageLoader";
    private static final boolean DEBUG = false;

    @NonNull
    private final ModelLoader<Uri, InputStream> mUriLoader;

    MediaInfoImageLoader(@NonNull ModelLoader<Uri, InputStream> uriLoader) {
        if (DEBUG) {
            Log.d(LOG_TAG, "MediaInfoLoader: uriLoader=" + uriLoader);
        }

        mUriLoader = uriLoader;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull FileInfo fileInfo, int width, int height, @NonNull Options options) {
        if (DEBUG) {
            Log.d(LOG_TAG, "buildLoadData: mediaInfo=" + fileInfo + " width=" + width + " height=" + height + " options=" + options);
        }

        return mUriLoader.buildLoadData(fileInfo.getUri(), width, height, options);
    }

    @Override
    public boolean handles(@NonNull FileInfo fileInfo) {
        return fileInfo.isImage();
    }

    public static class Factory implements ModelLoaderFactory<FileInfo, InputStream> {

        @NonNull
        public static Factory create() {

            return new Factory();
        }


        private Factory() {

        }

        @NonNull
        @Override
        public ModelLoader<FileInfo, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {

            return new MediaInfoImageLoader(multiFactory.build(Uri.class, InputStream.class));
        }

        @Override
        public void teardown() {

        }
    }
}
