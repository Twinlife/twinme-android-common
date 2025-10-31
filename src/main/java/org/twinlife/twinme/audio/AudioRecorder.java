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

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.util.Utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
        IO_ERROR;

        private static final Map<BaseService.ErrorCode, ErrorCode> ERROR_CODES_MAPPING = Map.of(
                BaseService.ErrorCode.NO_STORAGE_SPACE, ErrorCode.NO_STORAGE_SPACE,
                BaseService.ErrorCode.FILE_NOT_FOUND, ErrorCode.EMPTY_FILE,
                BaseService.ErrorCode.NO_PERMISSION, ErrorCode.IO_ERROR);

        public static ErrorCode fromBaseServiceErrorCode(@NonNull BaseService.ErrorCode errorCode) {
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
            cleanSegments();
        }

        @Override
        public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult, @NonNull ExportException exportException) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onError: composition=" + composition + " exportResult=" + exportResult + " exportException=" + exportException);
            }

            handleError(ErrorCode.TRANSFORMER_ERROR, exportException);
            cleanSegments();
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
                // TODO REC: notify listener of playback error?
                stopPlayback();
            }
        };

        @UiThread
        private synchronized void startPlayback() {
            if (DEBUG) {
                Log.d(LOG_TAG, "startPlayback");
            }

            if (mIsRecording) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Cannot start playback while recording is active.");
                }
                mListener.onPlaybackStopped();
                return;
            }

            if (mAudioSegments.isEmpty()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "No audio segments to play.");
                }
                mListener.onPlaybackStopped();
                return;
            }

            if (exoPlayer.isPlaying()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Playback is already in progress.");
                }
                return;
            }

            List<MediaItem> mediaItems = new ArrayList<>();
            for (File segmentFile : mAudioSegments) {
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
        private synchronized void pausePlayback() {
            if (DEBUG) {
                Log.d(LOG_TAG, "pausePlayback");
            }

            exoPlayer.setPlayWhenReady(!exoPlayer.isPlaying());
        }

        @UiThread
        private synchronized void stopPlayback() {
            if (DEBUG) {
                Log.d(LOG_TAG, "internalStopPlayback");
            }

            exoPlayer.stop();
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
        mExecutor.execute(this::internalStart);
    }

    public void stop() {
        mExecutor.execute(this::internalStop);
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
        mExecutor.execute(this::internalGetRecording);
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

            internalRelease();

            handleError(ErrorCode.MEDIA_RECORDER_ERROR, exception);
        }

        mSegmentId++;
        mAudioSegments.add(audioSegment);
        mIsRecording = true;
        mMainThreadHandler.postDelayed(this::updateTimer, TIMER_REFRESH_RATE);

        mMainThreadHandler.post(mListener::onRecordingStarted);
    }

    private synchronized void internalStop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "internalStop");
        }

        if (!mIsRecording || mRecorder == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "not recording");
            }
            return;
        }

        try {
            mRecorder.stop();
            internalRelease();
        } catch (Exception exception) {
            handleError(ErrorCode.MEDIA_RECORDER_ERROR, exception);
            return;
        }

        mIsRecording = false;

        mMainThreadHandler.post(mListener::onRecordingStopped);
    }

    private synchronized void internalGetRecording() {
        if (DEBUG) {
            Log.d(LOG_TAG, "internalGetRecording");
        }

        if (mIsRecording) {
            if (DEBUG) {
                Log.d(LOG_TAG, "still recording");
            }
            return;
        }

        if (mAudioSegments.isEmpty()) {
            handleError(ErrorCode.EMPTY_FILE, new Exception("No audio segments"));
            mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
            return;
        }

        if (mAudioSegments.size() == 1) {
            File segment = mAudioSegments.get(0);

            if (!segment.exists() || segment.length() == 0) {
                handleError(ErrorCode.EMPTY_FILE, new Exception("Lone segment is empty"));
                mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                return;
            }

            try {
                mOutput = File.createTempFile("rec", ".mp4", mContext.getCacheDir());
                if (!segment.renameTo(mOutput)) {
                    BaseService.ErrorCode errorCode = Utils.copyFile(segment, mOutput);
                    if (errorCode != BaseService.ErrorCode.SUCCESS) {
                        handleError(ErrorCode.fromBaseServiceErrorCode(errorCode), new Exception("Utils.copyFile() failed"));
                        mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                        return;
                    }
                }
                mAudioSegments.clear();
            } catch (Exception e) {
                handleError(ErrorCode.IO_ERROR, e);
                mMainThreadHandler.post(() -> mListener.onRecordingReady(null));
                return;
            }
            mMainThreadHandler.post(() -> mListener.onRecordingReady(mOutput));
            return;
        }

        concatenateSegments();
    }

    @OptIn(markerClass = UnstableApi.class)
    private synchronized void concatenateSegments() {
        if (DEBUG) {
            Log.d(LOG_TAG, "concatenateSegments, " + mAudioSegments.size() + " segments");
        }

        EditedMediaItemSequence.Builder builder = new EditedMediaItemSequence.Builder();

        for (File segment : mAudioSegments) {
            builder.addItem(new EditedMediaItem.Builder(MediaItem.fromUri(segment.getPath())).build());
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
            Log.d(LOG_TAG, "releaseRecorder");
        }

        if (mRecorder != null) {
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

        mMainThreadHandler.post(() -> mListener.onRecordingError(errorCode, segmentInfo, exception));
    }

    @NonNull
    private String buildSegmentInfo() {

        if (mAudioSegments.isEmpty()) {
            return "Segment info: no segment";
        }

        try {
            StringBuilder messageBuilder = new StringBuilder("Segments info: [");

            for (int i = 0; i < mAudioSegments.size(); i++) {
                File segment = mAudioSegments.get(i);
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

    private synchronized void cleanSegments() {
        for (File segment : mAudioSegments) {
            if (!segment.delete()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Failed to delete segment: " + segment.getPath());
                }
            }
        }
        mAudioSegments.clear();
    }
}
