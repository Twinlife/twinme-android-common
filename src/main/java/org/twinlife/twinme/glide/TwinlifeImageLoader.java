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
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;

import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinme.TwinmeContext;

/**
 * Support to create the TwinlifeImageFetcher
 */
public class TwinlifeImageLoader implements ModelLoader<ImageId, Bitmap> {
    private static final String LOG_TAG = "TwinlifeImageLoader";
    private static final boolean DEBUG = false;

    @NonNull
    private final ImageService mImageService;

    TwinlifeImageLoader(@NonNull ImageService imageService) {

        mImageService = imageService;
    }

    /**
     * Returns a {@link LoadData} containing a {@link
     * DataFetcher} required to decode the resource represented by this
     * model, as well as a set of {@link Key Keys} that identify the data
     * loaded by the {@link DataFetcher} as well as an optional list of
     * alternate keys from which equivalent data can be loaded. The {@link DataFetcher} will not be
     * used if the resource is already cached.
     *
     * <p>Note - If no valid data fetcher can be returned (for example if a model has a null URL),
     * then it is acceptable to return a null data fetcher from this method.
     *
     * @param imageId The model representing the resource.
     * @param width   The width in pixels of the view or target the resource will be loaded into, or
     *                {@link Target#SIZE_ORIGINAL} to indicate that the
     *                resource should be loaded at its original width.
     * @param height  The height in pixels of the view or target the resource will be loaded into, or
     *                {@link Target#SIZE_ORIGINAL} to indicate that the
     *                resource should be loaded at its original height.
     * @param options
     */
    @Nullable
    @Override
    public LoadData<Bitmap> buildLoadData(@NonNull ImageId imageId, int width, int height, @NonNull Options options) {
        final Boolean normal = options.get(Modes.NORMAL);
        final boolean isThumbnail = !Boolean.TRUE.equals(normal);

        if (DEBUG) {
            Log.d(LOG_TAG, "buildLoadData " + imageId + " " + width + "x" + height + " thumbnail=" + isThumbnail);
        }

        return new LoadData<>(new ObjectKey(imageId + "." + isThumbnail),
                new TwinlifeImageFetcher(mImageService, imageId, isThumbnail));
    }

    /**
     * Returns true if the given model is a of a recognized type that this loader can probably load.
     *
     * <p>For example, you may want multiple Uri to InputStream loaders. One might handle media store
     * Uris, another might handle asset Uris, and a third might handle file Uris etc.
     *
     * <p>This method is generally expected to do no I/O and complete quickly, so best effort results
     * are acceptable. {@link ModelLoader ModelLoaders} that return true from this method may return
     * {@code null} from buildLoadData(Object, int, int, Options)
     *
     * @param imageId
     */
    @Override
    public boolean handles(@NonNull ImageId imageId) {

        return true;
    }

    public static class Factory implements ModelLoaderFactory<ImageId, Bitmap> {

        @NonNull
        public static Factory create(@NonNull TwinmeContext twinmeContext) {

            return new Factory(twinmeContext);
        }

        @NonNull
        private final TwinmeContext mTwinmeContext;

        private Factory(@NonNull TwinmeContext twinmeContext) {

            mTwinmeContext = twinmeContext;
        }

        /**
         * Build a concrete ModelLoader for this model type.
         *
         * @param multiFactory A map of classes to factories that can be used to construct additional
         *                     {@link ModelLoader}s that this factory's {@link ModelLoader} may depend on
         * @return A new {@link ModelLoader}
         */
        @NonNull
        @Override
        public ModelLoader<ImageId, Bitmap> build(@NonNull MultiModelLoaderFactory multiFactory) {

            return new TwinlifeImageLoader(mTwinmeContext.getImageService());
        }

        /**
         * A lifecycle method that will be called when this factory is about to replaced.
         */
        @Override
        public void teardown() {

        }
    }
}
