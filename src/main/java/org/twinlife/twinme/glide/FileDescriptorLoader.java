/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.glide;

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

import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Descriptor.Type;
import org.twinlife.twinlife.ConversationService.FileDescriptor;
import org.twinlife.twinme.TwinmeContext;

import java.io.File;
import java.io.InputStream;

/**
 * Support to create the FileDescriptorFetcher for an image/video descriptor.
 */
public class FileDescriptorLoader implements ModelLoader<FileDescriptor, InputStream> {
    private static final String LOG_TAG = "FileDescriptorLoader";
    private static final boolean DEBUG = false;

    @NonNull
    private final File mFilesDir;
    @NonNull
    private final ConversationService mConversationService;
    @NonNull
    private final ModelLoader<File, InputStream> mFileLoader;

    FileDescriptorLoader(@NonNull ModelLoader<File, InputStream> fileLoader, @NonNull ConversationService conversationService, @NonNull File filesDir) {

        mFileLoader = fileLoader;
        mConversationService = conversationService;
        mFilesDir = filesDir;
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
     * @param fileDescriptor   The model representing the resource.
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
    public LoadData<InputStream> buildLoadData(@NonNull FileDescriptor fileDescriptor, int width, int height, @NonNull Options options) {
        final boolean thumbnail = fileDescriptor.getType() == Type.VIDEO_DESCRIPTOR || Boolean.TRUE.equals(options.get(Modes.THUMBNAIL));

        if (DEBUG) {
            Log.d(LOG_TAG, "buildLoadData " + fileDescriptor.getDescriptorId() + " in " + width + "x" + height + " thumbnail=" + thumbnail);
        }

        File file = thumbnail ? mConversationService.getDescriptorThumbnailFile(fileDescriptor) : null;
        if (file == null && fileDescriptor.isAvailable()) {
            file = new File(mFilesDir, fileDescriptor.getPath());
        }

        if (file == null) {
            return null;
        }

        return mFileLoader.buildLoadData(file, width, height, options);
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
    public boolean handles(@NonNull FileDescriptor imageId) {

        return true;
    }

    public static class Factory implements ModelLoaderFactory<FileDescriptor, InputStream> {

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
        public ModelLoader<FileDescriptor, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {

            return new FileDescriptorLoader(multiFactory.build(File.class, InputStream.class), mTwinmeContext.getConversationService(), mTwinmeContext.getFilesDir());
        }

        /**
         * A lifecycle method that will be called when this factory is about to replaced.
         */
        @Override
        public void teardown() {

        }
    }
}
