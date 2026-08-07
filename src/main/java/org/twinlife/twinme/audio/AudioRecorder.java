/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.audio;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.UiThread;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.TransformationRequest;
import androidx.media3.transformer.Transformer;

import org.twinlife.twinlife.util.Utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioRecorder {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "AudioRecorder";

    private static final int TIMER_REFRESH_RATE = 100; //ms

    public enum ErrorCode {
        MEDIA_RECORDER_ERROR,
        TRANSFORMER_ERROR,
        EMPTY_FILE,
        NO_STORAGE_SPACE,
        IO_ERROR,
        MEDIA_PLAYER_ERROR;

        private static final Map<org.twinlife.twinlife.ErrorCode, ErrorCode> ERROR_CODES_MAPPING = Map.of(
                org.twinlife.twinlife.ErrorCode.NO_STORAGE_SPACE, ErrorCode.NO_STORAGE_SPACE,
                org.twinlife.twinlife.ErrorCode.FILE_NOT_FOUND, ErrorCode.EMPTY_FILE,
                org.twinlife.twinlife.ErrorCode.NO_PERMISSION, ErrorCode.IO_ERROR);

        public static ErrorCode fromBaseServiceErrorCode(@NonNull org.twinlife.twinlife.ErrorCode errorCode) {
            ErrorCode mapping = ERROR_CODES_MAPPING.get(errorCode);
            return mapping != null ? mapping : ErrorCode.IO_ERROR;
        }
    }

    public interface AudioRecorderListener {
        void onRecordingStarted();

        void onRecordingStopped();

        void onPlaybackStopped();

        void onRecordingError(@NonNull ErrorCode errorCode, @Nullable String message, @Nullable Exception exception);

        void onTimerUpdated(long duration, int amplitude);

        /**
         * Called if the recording is made up of several segments that need to be merged.
         * If there is a single segment, onRecordingReady() will be called instantly.
         */
        void onRecordingProcessing();

        void onRecordingReady(@Nullable File recording);
    }

    @OptIn(markerClass = UnstableApi.class)
    private class TransformerListener implements Transformer.Listener {
        @Override
        public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onCompleted: composition=" + composition + " exportResult=" + exportResult);
            }

            mMainThreadHandler.post(() -> mListener.onRecordingReady(mOutput));
        }

        @Override
        public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult, @NonNull ExportException exportException) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onError: composition=" + composition + " exportResult=" + exportResult + " exportException=" + exportException);
            }

            if (!mExecutor.isShutdown()) {
                mExecutor.execute(() -> handleError(ErrorCode.TRANSFORMER_ERROR, exportException));
            }
            release();
        }

        @Override
        public void onFallbackApplied(@NonNull Composition composition, @NonNull TransformationRequest originalTransformationRequest, @NonNull TransformationRequest fallbackTransformationRequest) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onFallbackApplied: composition=" + composition + " originalTransformationRequest=" + originalTransformationRequest + " fallbackTransformationRequest=" + fallbackTransformationRequest);
            }

            // This shouldn't happen?
        }
    }

    private class Player {
        @NonNull
        private final ExoPlayer exoPlayer = new ExoPlayer.Builder(mContext).build();
        private long playPosition = 0;

        @NonNull
        private final ExoPlayer.Listener exoPlayerListener = new ExoPlayer.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "onPlaybackStateChanged: playbackState=" + playbackState);
                }

                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    Log.i(LOG_TAG, "All segments finished playing.");
                    mMainThreadHandler.post(mListener::onPlaybackStopped);
                } else if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    Log.d(LOG_TAG, "Playback buffering...");
                } else if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    Log.d(LOG_TAG, "Playback ready.");
                }
            }

            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                Log.e(LOG_TAG, "ExoPlayer error during playback", error);
                mExecutor.execute(() -> handleError(ErrorCode.MEDIA_PLAYER_ERROR, error));
                stopPlayback();
            }
        };

        @UiThread
        private void startPlayback() {
            if (DEBUG) {
                Log.d(LOG_TAG, "startPlayback");
            }

            boolean isRecording;
            boolean isPlaying;
            List<File> segments;

            synchronized (AudioRecorder.this) {
                isRecording = mIsRecording;
                isPlaying = exoPlayer.isPlaying();
                segments = getAudioSegments();
            }

            if (isRecording) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Cannot start playback while recording is active.");
                }
                mListener.onPlaybackStopped();
                return;
            }

            if (segments.isEmpty()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "No audio segments to play.");
                }
                mListener.onPlaybackStopped();
                return;
            }

            if (isPlaying) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Playback is already in progress.");
                }
                return;
            }

            List<MediaItem> mediaItems = new ArrayList<>();
            for (File segmentFile : segments) {
                if (segmentFile.exists()) {
                    mediaItems.add(MediaItem.fromUri(Uri.fromFile(segmentFile)));
                }
            }

            if (mediaItems.isEmpty()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "No audio segments found to play.");
                }
                stopPlayback();
                return;
            }

            exoPlayer.addListener(exoPlayerListener);

            exoPlayer.setMediaItems(mediaItems);
            exoPlayer.prepare();
            exoPlayer.play();

            playPosition = 0;
            mMainThreadHandler.postDelayed(this::updatePlayPosition, TIMER_REFRESH_RATE);
        }

        @UiThread
        private void updatePlayPosition() {
            if (!exoPlayer.isPlaying()) {
                return;
            }

            playPosition += TIMER_REFRESH_RATE;
            mMainThreadHandler.postDelayed(this::updatePlayPosition, TIMER_REFRESH_RATE);
        }

        @UiThread
        private void pausePlayback() {
            if (DEBUG) {
                Log.d(LOG_TAG, "pausePlayback");
            }

            synchronized (AudioRecorder.this) {
                exoPlayer.setPlayWhenReady(!exoPlayer.isPlaying());
            }
        }

        @UiThread
        private void stopPlayback() {
            if (DEBUG) {
                Log.d(LOG_TAG, "internalStopPlayback");
            }

            synchronized (AudioRecorder.this) {
                exoPlayer.stop();
            }
            mListener.onPlaybackStopped();
        }

        private void release() {

            mMainThreadHandler.post(exoPlayer::release);
        }
    }

    @NonNull
    private final Context mContext;
    @NonNull
    private final AudioRecorderListener mListener;
    @NonNull
    private final List<File> mAudioSegments = new ArrayList<>();
    @NonNull
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "AudioRecorder"));

    @Nullable
    private MediaRecorder mRecorder;
    private int mSegmentId = 0;
    private boolean mIsRecording = false;
    private long mRecordingTotalTime = 0;

    @Nullable
    private Player mPlayer = null;

    @NonNull
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private File mOutput;

    public AudioRecorder(@NonNull Context context, @NonNull AudioRecorderListener listener) {
        mContext = context;
        mListener = listener;
    }

    public void start() {
        // Be careful that even for start(), the executor could have been shutdown.
        if (!mExecutor.isShutdown()) {
            mExecutor.execute(this::internalStart);
        }
    }

    public void stop() {
        if (!mExecutor.isShutdown()) {
            mExecutor.execute(this::internalStop);
        }
    }

    @UiThread
    public synchronized void startPlayback() {
        if (mPlayer == null) {
            mPlayer = new Player();
        }

        mPlayer.startPlayback();
    }

    @UiThread
    public synchronized void pausePlayback() {
        if (mPlayer != null) {
            mPlayer.pausePlayback();
        }
    }

    @UiThread
    public synchronized void stopPlayback() {
        if (mPlayer != null) {
            mPlayer.stopPlayback();
        }
    }

    public void release() {
        if (DEBUG) {
            Log.d(LOG_TAG, "release");
        }
        if (mExecutor.isShutdown()) {
            // Release already called
            return;
        }

        mExecutor.execute(() -> {
            internalRelease();
            cleanSegments();
        });
        mExecutor.shutdown();
    }

    public synchronized void getRecording() {
        if (mPlayer != null) {
            mPlayer.stopPlayback();
        }
        if (!mExecutor.isShutdown()) {
            mExecutor.execute(this::internalGetRecording);
        }
    }

    public synchronized long getDuration() {
        return mRecordingTotalTime;
    }

    public synchronized boolean isRecording() {
        return mIsRecording;
    }

    public synchronized long getPlayPosition() {
        return mPlayer == null ? 0 : mPlayer.playPosition;
    }


    private synchronized void internalStart() {
        if (DEBUG) {
            Log.d(LOG_TAG, "internalStart");
        }

        if (mIsRecording) {
            if (DEBUG) {
                Log.d(LOG_TAG, "already recording");
            }
            return;
        }

        mRecorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                new MediaRecorder(mContext) :
                new MediaRecorder();

        File audioSegment = null;
        try {
            audioSegment = File.createTempFile("segmentId" + mSegmentId, ".mp4", mContext.getCacheDir());
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mRecorder.setPrivacySensitive(true);
            }
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setOutputFile(audioSegment.getPath());
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioEncodingBitRate(32000);
            mRecorder.setAudioChannels(1);
            mRecorder.setOnErrorListener(this::onMediaRecorderError);
            mRecorder.prepare();
            mRecorder.start();

        } catch (Exception exception) {

            if (audioSegment != null) {
                if (!audioSegment.delete()) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "Failed to delete segment: " + audioSegment.getPath());
                    }
                }
            }

            handleError(ErrorCode.MEDIA_RECORDER_ERROR, exception);

            release();

            return;
        }

        mSegmentId++;
        addAudioSegment(audioSegment);
        mIsRecording = true;
        mMainThreadHandler.postDelayed(this::updateTimer, TIMER_REFRESH_RATE);

        mMainThreadHandler.post(mListener::onRecordingStarted);
    }

    private synchronized void onMediaRecorderError(MediaRecorder mr, int what, int extra) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMediaRecorderError, what=" + what + " extra=" + extra);
        }

        handleError(ErrorCode.MEDIA_RECORDER_ERROR, new Exception("MediaRecorder error: " + what + ", " + extra));

        release();
    }

    private synchronized boolean internalStop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "internalStop");
        }

        if (!mIsRecording || mRecorder == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "not recording");
            }
            return true;
        }

        try {
            mRecorder.stop();
        } catch (Exception exception) {
            handleError(ErrorCode.MEDIA_RECORDER_ERROR, exception);
            return false;
        } finally {
            internalRelease();
            mIsRecording = false;
        }

        mMainThreadHandler.post(mListener::onRecordingStopped);
        return true;
    }

    private synchronized void internalGetRecording() {
        if (DEBUG) {
            Log.d(LOG_TAG, "internalGetRecording");
        }

        if (mIsRecording) {
            if (!internalStop()) {
                mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                return;
            }
        }

        List<File> audioSegments = getAudioSegments();

        if (audioSegments.isEmpty()) {
            handleError(ErrorCode.EMPTY_FILE, new Exception("No audio segments"));
            mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
            return;
        }

        if (audioSegments.size() == 1) {
            File segment = audioSegments.get(0);

            if (!segment.exists() || segment.length() == 0) {
                handleError(ErrorCode.EMPTY_FILE, new Exception("Lone segment is empty"));
                mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                return;
            }

            try {
                mOutput = File.createTempFile("rec", ".mp4", mContext.getCacheDir());
                if (!segment.renameTo(mOutput)) {
                    org.twinlife.twinlife.ErrorCode errorCode = Utils.copyFile(segment, mOutput);
                    if (errorCode != org.twinlife.twinlife.ErrorCode.SUCCESS) {
                        handleError(ErrorCode.fromBaseServiceErrorCode(errorCode), new Exception("Utils.copyFile() failed"));
                        mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                        return;
                    }
                }
                cleanSegments();
            } catch (Exception e) {
                handleError(ErrorCode.IO_ERROR, e);
                mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                return;
            }
            mMainThreadHandler.post(() -> mListener.onRecordingReady(mOutput));
            return;
        }

        mMainThreadHandler.post(mListener::onRecordingProcessing);

        concatenateSegments();
    }

    @OptIn(markerClass = UnstableApi.class)
    private synchronized void concatenateSegments() {
        List<File> segments = getAudioSegments();
        if (DEBUG) {
            Log.d(LOG_TAG, "concatenateSegments, " + segments.size() + " segments");
        }

        EditedMediaItemSequence.Builder builder = new EditedMediaItemSequence.Builder(Collections.singleton(C.TRACK_TYPE_AUDIO));

        for (File segment : segments) {
            builder.addItem(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(segment))).build());
        }

        EditedMediaItemSequence sequence = builder.build();

        Composition composition = new Composition.Builder(sequence).setTransmuxAudio(true).build();

        Transformer transformer = new Transformer.Builder(mContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new TransformerListener())
                .build();

        try {
            mOutput = File.createTempFile("rec", ".mp4", mContext.getCacheDir());
        } catch (IOException exception) {
            handleError(ErrorCode.IO_ERROR, exception);
            return;
        }

        // Transformer MUST be called on the main thread.
        mMainThreadHandler.post(() -> transformer.start(composition, mOutput.getPath()));
    }

    @UiThread
    private void updateTimer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateTimer");
        }

        MediaRecorder recorder;
        long recordingTotalTime;
        synchronized (this) {
            if (!mIsRecording || mRecorder == null) {
                return;
            }

            mRecordingTotalTime += TIMER_REFRESH_RATE;

            recorder = mRecorder;
            recordingTotalTime = mRecordingTotalTime;
        }

        int maxAmplitude = -1;
        try {
            // On some devices getMaxAmplitude() throws an IllegalStateException.
            // This should only happen if the
            maxAmplitude = recorder.getMaxAmplitude();
        } catch (Exception e) {
            Log.e(LOG_TAG, "getMaxAmplitude failed", e);
        }

        if (maxAmplitude >= 0) {
            mListener.onTimerUpdated(recordingTotalTime, maxAmplitude);
        }
        mMainThreadHandler.postDelayed(this::updateTimer, TIMER_REFRESH_RATE);
    }

    private synchronized void internalRelease() {
        if (DEBUG) {
            Log.d(LOG_TAG, "internalRelease");
        }

        if (mRecorder != null) {
            try {
                mRecorder.reset();
            } catch (Exception e) {
                // Reset can fail if the recorder is in an error state.
                Log.e(LOG_TAG, "reset failed", e);
            }
            mRecorder.release();
            mRecorder = null;
        }

        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    private void handleError(@NonNull ErrorCode errorCode, @Nullable Exception exception) {
        Log.e(LOG_TAG, "Recording error: " + errorCode, exception);

        String segmentInfo = buildSegmentInfo();

        Exception wrapped = exception != null ?
                new Exception(segmentInfo, exception) :
                new Exception(segmentInfo);

        mMainThreadHandler.post(() -> mListener.onRecordingError(errorCode, segmentInfo, wrapped));
    }

    @NonNull
    private String buildSegmentInfo() {
        if (DEBUG) {
            Log.d(LOG_TAG, "buildSegmentInfo");
        }

        List<File> segments = getAudioSegments();

        if (segments.isEmpty()) {
            return "Segment info: no segment";
        }

        try {
            StringBuilder messageBuilder = new StringBuilder("Segments info: [");

            for (int i = 0; i < segments.size(); i++) {
                File segment = segments.get(i);
                messageBuilder.append(i).append(": exists=").append(segment.exists())
                        .append(" length=").append(segment.length())
                        .append(", ");
            }
            messageBuilder.delete(messageBuilder.length() - 2, messageBuilder.length());
            messageBuilder.append("]");
            return messageBuilder.toString();
        } catch (Exception e) {
            return "Could not get segments info: " + e.getMessage();
        }

    }

    private void addAudioSegment(@NonNull File audioSegment) {
        if (DEBUG) {
            Log.d(LOG_TAG, "addAudioSegment: audioSegment=" + audioSegment);
        }

        synchronized (mAudioSegments) {
            mAudioSegments.add(audioSegment);
        }
    }

    private List<File> getAudioSegments() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAudioSegments");
        }

        synchronized (mAudioSegments) {
            return new ArrayList<>(mAudioSegments);
        }
    }

    private void cleanSegments() {
        if (DEBUG) {
            Log.d(LOG_TAG, "cleanSegments");
        }

        List<File> toDelete;

        synchronized (mAudioSegments) {
            toDelete = new ArrayList<>(mAudioSegments);
            mAudioSegments.clear();
        }

        for (File segment : toDelete) {
            if (segment.exists() && !segment.delete()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Failed to delete segment: " + segment.getPath());
                }
            }
        }
    }
}
