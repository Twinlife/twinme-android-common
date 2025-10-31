/*
 *  Copyright (c) 2022-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import org.twinlife.twinlife.PeerConnectionService;
import org.twinlife.twinme.calls.CallConnection;
import org.twinlife.twinme.calls.CallState;
import org.twinlife.twinme.utils.MediaMetaData;

import org.twinlife.twinme.calls.streaming.StreamingControlIQ.Mode;

import static org.twinlife.twinme.calls.streaming.StreamingControlIQ.IQ_STREAMING_CONTROL_SERIALIZER;

/**
 * A media stream player to play either the local streamer or a stream received during an Audio/Video call.
 */
@SuppressLint("UnsafeOptInUsageError")
public final class StreamPlayerImpl implements StreamPlayer, Player.Listener {
    private static final String LOG_TAG = "StreamPlayer";
    private static final boolean DEBUG = false;
    private static final int EXO_MIN_BUFFER_MS = 1000;
    private static final int EXO_MAX_BUFFER_MS = 1500;
    private static final int EXO_PLAYBACK_BUFFER_MS = EXO_MIN_BUFFER_MS;

    @NonNull
    private final CallState mCall;
    @Nullable
    private final CallConnection mConnection;
    private final long mStreamIdent;
    private final boolean mVideo;
    @NonNull
    private final StreamDataSource mDataSource;
    @Nullable
    private final StreamerImpl mLocalStreamer;
    private final Handler mHandler;
    private final Runnable mRefresh;
    private boolean mReady;
    private boolean mPaused;
    @Nullable
    private volatile ExoPlayer mPlayer;
    @Nullable
    private MediaMetaData mMediaInfo;
    private long mLastPlayerPosition;
    private long mLastPlayerPositionTime;

    @Override
    @Nullable
    public MediaMetaData getMediaInfo() {

        return mMediaInfo;
    }

    public long getIdent() {

        return mStreamIdent;
    }

    @Override
    @Nullable
    public Streamer getStreamer() {

        return mLocalStreamer;
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPlaybackStateChanged playbackState=" + playbackState);
        }

        final ExoPlayer player;
        final Mode mode;
        synchronized (this) {
            if (playbackState == Player.STATE_ENDED) {
                mReady = false;
                player = mPlayer;
                mPlayer = null;
                mode = Mode.STREAMING_STATUS_COMPLETED;

            } else if (playbackState == Player.STATE_READY && !mReady) {
                mReady = true;
                mode = Mode.STREAMING_STATUS_PLAYING;
                player = null;

            } else {
                mode = null;
                player = null;
            }
        }

        if (mode != null) {
            sendStreamControl(mode, 0);
        }
        if (player != null) {
            mHandler.post(player::release);
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException  error) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPlayerError error code=" + error.errorCode, error);
        }

        final Mode mode;
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                mode = Mode.STREAMING_STATUS_UNSUPPORTED;
                break;

            default:
                mode = Mode.STREAMING_STATUS_ERROR;
                break;
        }

        sendStreamControl(mode, 0);
    }

    /**
     * Check if the media stream is a video stream.
     *
     * @return true if the stream is a video stream.
     */
    @Override
    public boolean isVideo() {

        return mVideo;
    }

    /**
     * Check if the current player is in pause
     *
     * @return true if the player is in pause.
     */
    @Override
    public boolean isPause() {

        return mPaused;
    }

    public StreamPlayerImpl(long ident, long size, boolean video, @NonNull CallState call,
                            @Nullable CallConnection connection,
                            @Nullable StreamerImpl localStreamer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "CallMediaStream ident=" + ident + " size=" + size);
        }

        mHandler = call.getHandler();
        mRefresh = this::refreshPosition;
        mDataSource = new StreamDataSource(ident, call, connection, localStreamer, this);
        mStreamIdent = ident;
        mCall = call;
        mVideo = video;
        mReady = false;
        mPaused = false;
        mConnection = connection;
        mLocalStreamer = localStreamer;

        final DefaultLoadControl.Builder loadControl = new DefaultLoadControl.Builder();
        loadControl.setAllocator(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE));
        loadControl.setBufferDurationsMs(EXO_MIN_BUFFER_MS, EXO_MAX_BUFFER_MS, EXO_PLAYBACK_BUFFER_MS, EXO_PLAYBACK_BUFFER_MS);

        final ExoPlayer player = new ExoPlayer.Builder(call.getContext()).setLoadControl(loadControl.build()).build();
        player.addListener(this);
        mPlayer = player;

        mHandler.post(this::initialize);
    }

    private void initialize() {
        final ExoPlayer player = mPlayer;

        if (player != null) {
            final DataSource.Factory factory = () -> mDataSource;

            final MediaSource audioSource = new ProgressiveMediaSource.Factory(factory).createMediaSource(
                    MediaItem.fromUri(Uri.parse("webrtc://stream")));
            final AudioAttributes.Builder builder = new AudioAttributes.Builder();
            builder.setUsage(C.USAGE_MEDIA);
            builder.setContentType(C.AUDIO_CONTENT_TYPE_MUSIC);
            player.setAudioAttributes(builder.build(), false);

            player.setMediaSource(audioSource);
            player.prepare();
            player.play();
            refreshPosition();
        }
    }

    /**
     * Set the optional information that describes the stream being played by the peer.
     *
     * @param title the title
     * @param album the optional album
     * @param artist the optional artist
     * @param artwork the optional artwork picture.
     * @param duration the stream duration.
     */
    public void setInformation(@NonNull String title, @Nullable String album, @Nullable String artist,
                               @Nullable Bitmap artwork, long duration) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setInformation title=" + title + " album=" + album + " artist=" + artist);
        }

        mMediaInfo = new MediaMetaData(mVideo ? MediaMetaData.Type.VIDEO : MediaMetaData.Type.AUDIO, artist, album, title,
                artwork, duration, null);
    }

    /**
     * Get the current player position in milliseconds.
     *
     * @param now the current timestamp.
     * @return the current player position.
     */
    @Override
    public long getCurrentPosition(long now) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentPosition");
        }

        // Note: we are called from signaling thread or another thread and
        // the ExoPlayer getCurrentPosition() must be called from the main UI thread.
        // We must not block to get the position, use the last value refreshed each
        // 5s and guess the new position since the value was fetched.
        final long lastPosition;
        final long lastTime;
        synchronized (this) {
            lastPosition = mLastPlayerPosition;
            lastTime = mLastPlayerPositionTime;
        }

        if (lastTime == 0) {
            return 0;
        } else {
            return lastPosition + (now - lastTime);
        }
    }

    @Override
    public void askPause() {
        if (DEBUG) {
            Log.d(LOG_TAG, "askPause");
        }

        // Make sure our current player position is accurate before asking the operation.
        updateCurrentPosition();
        sendStreamControl(Mode.ASK_PAUSE_STREAMING, 0);
    }

    @Override
    public void askResume() {
        if (DEBUG) {
            Log.d(LOG_TAG, "askResume");
        }

        updateCurrentPosition();
        sendStreamControl(Mode.ASK_RESUME_STREAMING, 0);
    }

    @Override
    public void askSeek(long offset) {
        if (DEBUG) {
            Log.d(LOG_TAG, "askSeek offset=" + offset);
        }

        updateCurrentPosition();
        sendStreamControl(Mode.ASK_SEEK_STREAMING, offset);
    }

    @Override
    public void askStop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "askStop");
        }

        sendStreamControl(Mode.ASK_STOP_STREAMING, 0);
    }

    /**
     * Pause the media stream player (internal operation).
     *
     * @param delay the delay to wait in milliseconds before the pause.
     */
    public void pause(long delay) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pause in " + delay + " ms ready=" + mReady + " paused=" + mPaused);
        }

        synchronized (this) {
            if (!mReady || mPaused) {

                mPaused = true;
                return;
            }
            mPaused = true;
        }

        // Pause the player from the main UI thread (ExoPlayer constraint).
        mHandler.postDelayed(() -> {
            if (mConnection != null) {
                mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_PAUSED);
                sendStreamControl(Mode.STREAMING_STATUS_PAUSED, 0);
            }
            final ExoPlayer player = mPlayer;
            if (player != null) {
                try {
                    player.pause();
                    mHandler.removeCallbacks(mRefresh);
                } catch (Exception exception) {
                    Log.e(LOG_TAG, "Exception: ", exception);
                }
            }
        }, delay);
    }

    /**
     * Resume the media stream player (internal operation).
     *
     * @param delay the delay to wait in milliseconds before the resume.
     */
    public void resume(long delay) {
        if (DEBUG) {
            Log.d(LOG_TAG, "resume in " + delay + " ms ready=" + mReady + " paused=" + mPaused);
        }

        synchronized (this) {
            if (!mReady || !mPaused) {

                return;
            }
            mPaused = false;
        }

        // Resume the player from the main UI thread (ExoPlayer constraint)
        mHandler.postDelayed(() -> {
            if (mConnection != null) {
                mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_PLAYING);
                sendStreamControl(Mode.STREAMING_STATUS_PLAYING, 0);
            }
            final ExoPlayer player = mPlayer;
            if (player != null) {
                try {
                    mHandler.removeCallbacks(mRefresh);
                    refreshPosition();
                    player.play();
                } catch (Exception exception) {
                    Log.e(LOG_TAG, "Exception: ", exception);
                }
            }
        }, delay);
    }

    /**
     * Seek the media stream player to the given position (in milliseconds from the beginning).
     *
     * @param position the position to seek.
     */
    public void seek(long position) {
        if (DEBUG) {
            Log.d(LOG_TAG, "seek position=" + position);
        }

        synchronized (this) {
            if (!mReady) {

                return;
            }
        }

        // Seek the player from the main UI thread (ExoPlayer constraint).
        mHandler.post(() -> {
            final ExoPlayer player = mPlayer;
            if (player != null) {
                try {
                    player.seekTo(position);
                } catch (Exception exception) {
                    Log.e(LOG_TAG, "Exception: ", exception);
                }
            }
        });
    }

    /**
     * Stop playing the media stream and release the resources (internal operation).
     *
     * @param notifyPeer notify the peer we have stopped.
     */
    public void stop(boolean notifyPeer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "stop notifyPeer=" + notifyPeer);
        }

        if (notifyPeer) {
            sendStreamControl(Mode.STREAMING_STATUS_STOPPED, 0);
        }

        final ExoPlayer player;
        synchronized (this) {
            player = mPlayer;
            if (player == null) {

                return;
            }
            mReady = false;
            mPlayer = null;
        }

        // Stop the player from the main UI thread (ExoPlayer constraint).
        mHandler.post(() -> {
            try {
                mHandler.removeCallbacks(mRefresh);
                player.stop();
                player.release();
            } catch (Exception exception) {
                Log.e(LOG_TAG, "Exception: " + exception);
            }
        });
    }

    /**
     * Handle the StreamingControlIQ packet from the streamer to Pause/Resume.
     *
     * @param iq the streaming control iq.
     */
    public void onStreamingControlIQ(@NonNull StreamingControlIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingControlIQ: iq=" + iq);
        }

        if (iq.ident != mStreamIdent) {
            return;
        }

        switch (iq.control) {
            case PAUSE_STREAMING:
                mHandler.post(() -> doPausePlayer(iq.length));
                break;

            case RESUME_STREAMING:
                mHandler.post(() -> doResumePlayer(iq.length));
                break;

            default:
                break;
        }
    }

    private void doPausePlayer(long askPosition) {
        if (DEBUG) {
            Log.d(LOG_TAG, "doPausePlayer: askPosition=" + askPosition);
        }

        final long position = updateCurrentPosition();
        long delay = askPosition - position;
        if (delay < 0) {
            delay = 0;
        }
        pause(delay);
    }

    private void doResumePlayer(long askPosition) {
        if (DEBUG) {
            Log.d(LOG_TAG, "doResumePlayer: askPosition=" + askPosition);
        }

        final long position = updateCurrentPosition();
        long delay = position - askPosition;
        if (delay < 0) {
            delay = 0;
        }
        resume(delay);
    }

    /**
     * Handle the StreamingDataIQ packet.
     *
     * @param iq the streaming data iq.
     */
    public void onStreamingDataIQ(@NonNull StreamingDataIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingDataIQ: iq=" + iq);
        }

        if (iq.ident != mStreamIdent) {
            return;
        }

        mDataSource.onStreamingDataIQ(iq);
    }

    long updateCurrentPosition() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateCurrentPosition");
        }

        final ExoPlayer player = mPlayer;
        final long position;
        if (player != null) {
            position = player.getCurrentPosition();
            final long now = System.currentTimeMillis();
            if (DEBUG) {
                long computedPosition = getCurrentPosition(now);
                Log.d(LOG_TAG, "Computed - current pos=" + (computedPosition - position));
            }
            synchronized (this) {
                mLastPlayerPosition = position;
                mLastPlayerPositionTime = now;
            }
        } else {
            position = 0;
        }
        return position;
    }

    /**
     * Refresh the player position information for getCurrentPosition() to return
     * a quite accurate value without blocking.  Called from the main UI thread, per ExoPlayer requirement.
     */
    private void refreshPosition() {
        if (DEBUG) {
            Log.d(LOG_TAG, "refreshPosition");
        }

        final ExoPlayer player = mPlayer;
        if (player != null) {
            final long position = player.getCurrentPosition();
            synchronized (this) {
                mLastPlayerPosition = position;
                mLastPlayerPositionTime = System.currentTimeMillis();
            }
            mHandler.postDelayed(this::refreshPosition, 5_000);
        }
    }

    /**
     * Send the player streaming status or request to pause/resume/seek/stop to the peer.
     *
     * @param mode the streaming status or request of our player.
     * @param offset the offset position.
     */
    private void sendStreamControl(@NonNull Mode mode, long offset) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendStreamControl mode=" + mode + " offset=" + offset);
        }

        if (mConnection != null) {
            final int latency = mDataSource.getLatency();
            final long now = System.currentTimeMillis();
            final long position = getCurrentPosition(now);

            final StreamingControlIQ iq = new StreamingControlIQ(IQ_STREAMING_CONTROL_SERIALIZER, mCall.allocateRequestId(),
                    mStreamIdent, mode, offset, now, position, latency);
            mConnection.sendMessage(iq, PeerConnectionService.StatType.IQ_SET_PUSH_OBJECT);
            switch (mode) {
                case STREAMING_STATUS_READY:
                    mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_START);
                    break;

                case STREAMING_STATUS_PLAYING:
                    mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_PLAYING);
                    break;

                case STREAMING_STATUS_COMPLETED:
                    mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_COMPLETED);
                    break;

                case STREAMING_STATUS_ERROR:
                    mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_ERROR);
                    break;

                case STREAMING_STATUS_UNSUPPORTED:
                    mCall.onEventStreaming(mConnection.getMainParticipant(), StreamingEvent.EVENT_UNSUPPORTED);
                    break;

                default:
                    break;
            }

        } else if (mLocalStreamer != null) {
            mLocalStreamer.localPlayerStatus(mode, offset);
        }
    }
}
