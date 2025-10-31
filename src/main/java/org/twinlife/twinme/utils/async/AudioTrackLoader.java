/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils.async;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.ConversationService.AudioDescriptor;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.utils.AudioTrack;

import java.io.File;

/**
 * Load the audio track from the loader manager thread.
 */
public class AudioTrackLoader<T> implements Loader<T> {
    private static final String LOG_TAG = "AudioTrackLoader";
    private static final boolean DEBUG = false;

    @NonNull
    private final T mItem;
    private final int mNbLines;
    @Nullable
    private volatile AudioDescriptor mAudioDescriptor;
    @Nullable
    private volatile AudioTrack mAudioTrack;

    /**
     * Create the audio track loader instance.
     *
     * @param item            the item to redraw when the image is loaded.
     * @param audioDescriptor the aduio descriptor to load.
     * @param nbLines         the number of lines to draw.
     */
    public AudioTrackLoader(@NonNull T item, @NonNull AudioDescriptor audioDescriptor, int nbLines) {
        if (DEBUG) {
            Log.d(LOG_TAG, "AudioTrackLoader audioDescriptor=" + audioDescriptor + " nbLines=" + nbLines);
        }

        mItem = item;
        mAudioDescriptor = audioDescriptor;
        mNbLines = nbLines;
    }

    /**
     * Cancel loading the audio track.
     */
    public void cancel() {
        if (DEBUG) {
            Log.d(LOG_TAG, "cancel");
        }

        mAudioDescriptor = null;
    }

    /**
     * Get the audio track if it was loaded.
     *
     * @return the audio track or null if it was not yet loaded.
     */
    @Nullable
    public AudioTrack getAudioTrack() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAudioTrack");
        }

        return mAudioTrack;
    }

    /**
     * Load or try to load the audio to get and build the audio track for the display.
     *
     * @param context       the context.
     * @param twinmeContext the twinme context.
     * @return the object to redraw when the image was loaded or null if there is no change.
     */
    @Nullable
    public T loadObject(@NonNull Context context, @NonNull TwinmeContext twinmeContext) {
        if (DEBUG) {
            Log.d(LOG_TAG, "loadObject");
        }

        if (mAudioTrack != null) {

            return null;
        }

        final AudioDescriptor audioDescriptor = mAudioDescriptor;
        if (audioDescriptor == null) {

            return null;
        }

        File filesDir = twinmeContext.getFilesDir();
        if (filesDir == null) {

            return null;
        }

        File file = new File(filesDir, audioDescriptor.getPath());

        AudioTrack audioTrack = new AudioTrack();
        audioTrack.initTrack(file.getPath(), mNbLines);

        // Update the audio track member only when we have finished.
        mAudioTrack = audioTrack;

        return mItem;
    }
}
