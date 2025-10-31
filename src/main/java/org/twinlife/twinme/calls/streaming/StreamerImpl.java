/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AndroidImageTools;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.ImageTools;
import org.twinlife.twinlife.PeerConnectionService;
import org.twinlife.twinme.calls.CallConnection;
import org.twinlife.twinme.calls.CallState;
import org.twinlife.twinme.utils.MediaMetaData;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.twinlife.twinme.calls.streaming.StreamingControlIQ.Mode;

import static org.twinlife.twinme.calls.streaming.StreamingControlIQ.IQ_STREAMING_CONTROL_SERIALIZER;
import static org.twinlife.twinme.calls.streaming.StreamingDataIQ.IQ_STREAMING_DATA_SERIALIZER;
import static org.twinlife.twinme.calls.streaming.StreamingInfoIQ.IQ_STREAMING_INFO_SERIALIZER;

/**
 * The streamer is responsible for sending a media stream to every peer connection/participant to a call.
 */
public final class StreamerImpl implements Streamer {
    private static final String LOG_TAG = "Streamer";
    private static final boolean DEBUG = false;

    // Maximum latency that we take into account for the pause/resume to synchronize the streamer and player.
    // Above 1s, such latency is ignored as a protection as it could delay the pause/resume too much.
    private static final int MAX_LATENCY = 1000;

    static final class RemotePlayerInfo {
        long position;
        long lastdate;
        int latency;
        boolean paused;

        long getPosition(long now) {
            if (paused) {
                return position;
            } else {
                return position + (now - lastdate) + (long) latency;
            }
        }
    }

    @Nullable
    private InputStream mFileStream;
    private long mFileStreamPos;
    @NonNull
    private final ScheduledExecutorService mExecutor;
    @NonNull
    private final CallState mCall;
    @Nullable
    private final MediaMetaData mMediaMetaData;

    private final long mStreamIdent;
    private final boolean mVideo;
    private final Handler mHandler;
    private boolean mEndOfStream;
    private final TreeSet<StreamBuffer> mBuffers;
    private final Map<UUID, RemotePlayerInfo> mRemotePlayers;
    @Nullable
    private StreamPlayerImpl mLocalPlayer;

    public StreamerImpl(@NonNull CallState callState, long streamIdent, @Nullable MediaMetaData mediaMetaData,
                        @NonNull ScheduledExecutorService executor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "Streamer");
        }

        mCall = callState;
        mHandler = callState.getHandler();
        mExecutor = executor;
        mBuffers = new TreeSet<>(new StreamBufferComparator());
        mRemotePlayers = new HashMap<>();
        mMediaMetaData = mediaMetaData;
        mStreamIdent = streamIdent;
        mVideo = mediaMetaData != null && mediaMetaData.type == MediaMetaData.Type.VIDEO;
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
     * Start streaming a content represented by the given path and which is provided by the resolver.
     * Note: opening the input stream can be slow and block so this method returns immediately while
     * the stream input is opened.
     *
     * @param resolver the content resolver to use.
     * @param path the content to stream.
     */
    public void startStreaming(@NonNull ContentResolver resolver, @NonNull Uri path) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startStreaming: path=" + path);
        }

        mExecutor.execute(() -> {

            mCall.onEventStreaming(null, StreamingEvent.EVENT_START);
            try {
                long length;

                InputStream inputStream = resolver.openInputStream(path);
                synchronized (this) {
                    if (mFileStream != null) {

                        return;
                    }

                    mFileStream = inputStream;
                    mFileStreamPos = 0;
                    if (mMediaMetaData != null) {
                        mLocalPlayer = new StreamPlayerImpl(mStreamIdent, 0, mVideo, mCall, null, this);
                        mLocalPlayer.setInformation(mMediaMetaData.title, mMediaMetaData.album, mMediaMetaData.artist, mMediaMetaData.artwork, mMediaMetaData.duration);
                    }
                    length = 0;
                }
                sendStreamStart(resolver, length);

            } catch (Exception exception) {
                Log.e(LOG_TAG, "Exception ", exception);

                mCall.onEventStreaming(null, StreamingEvent.EVENT_ERROR);
            }
        });
    }

    /**
     * Get local player
     *
     */
    @Nullable
    @Override
    public StreamPlayer getPlayer() {

        return mLocalPlayer;
    }

    /**
     * Stop streaming the media to the peers.
     *
     * @param notify when true notify peers about the stop streaming action.
     */
    public void stopStreaming(boolean notify) {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopStreaming notify=" + notify);
        }

        final StreamPlayerImpl localPlayer;
        synchronized (this) {
            if (mFileStream != null) {
                try {
                    mFileStream.close();
                } catch (Exception exception) {
                    Log.d(LOG_TAG, "Close error", exception);
                }
                mFileStream = null;
            } else {
                notify = false;
            }
            mBuffers.clear();
            localPlayer = mLocalPlayer;
            mLocalPlayer = null;
        }

        if (notify) {
            sendStreamControl(StreamingControlIQ.Mode.STOP_STREAMING, 0, 0, 0);
        }
        if (localPlayer != null) {
            localPlayer.stop(false);
        }
        mCall.onEventStreaming(null, StreamingEvent.EVENT_STOP);
    }

    /**
     * Pause the streaming by sending a PAUSE_STREAMING message to each call participant.
     */
    @Override
    public void pauseStreaming() {
        if (DEBUG) {
            Log.d(LOG_TAG, "pauseStreaming");
        }

        // Make sure we have the last accurate player position (we are called from the main UI thread).
        if (mLocalPlayer != null) {
            mLocalPlayer.updateCurrentPosition();
        }
        mExecutor.execute(this::doPauseStreaming);
    }

    /**
     * Resume the streaming by sending a RESUME_STREAMING message to each call participant.
     */
    @Override
    public void resumeStreaming() {
        if (DEBUG) {
            Log.d(LOG_TAG, "resumeStreaming");
        }

        // Make sure we have the last accurate player position (we are called from the main UI thread).
        if (mLocalPlayer != null) {
            mLocalPlayer.updateCurrentPosition();
        }
        mExecutor.execute(this::doResumeStreaming);
    }

    /**
     * Seek the streaming by sending a SEEK_STREAMING message with the position to each call participant.
     *
     * @param offset the offset position in milliseconds from the beginning.
     */
    public void seekStreaming(long offset) {
        if (DEBUG) {
            Log.d(LOG_TAG, "seekStreaming offset=" + offset);
        }

        sendStreamControl(StreamingControlIQ.Mode.SEEK_STREAMING, offset, System.currentTimeMillis(), offset);
        if (mLocalPlayer != null) {
            mExecutor.execute(() -> {
                // Check again the local player instance and seek to the position.
                final StreamPlayerImpl player = mLocalPlayer;
                if (player != null) {
                    player.seek(offset);
                }
            });
        }
    }

    /**
     * Handle the StreamingControlIQ packet for the ASK_XXX operations requested by the peer.
     *
     * @param iq the streaming control iq.
     */
    public void onStreamingControlIQ(@NonNull CallConnection connection, @NonNull StreamingControlIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingControlIQ: iq=" + iq);
        }

        if (iq.ident != mStreamIdent) {
            return;
        }

        final long receiveTime = System.currentTimeMillis();
        final UUID peerConnectionId = connection.getPeerConnectionId();
        if (peerConnectionId == null) {
            return;
        }

        // Record this player position and latency.
        final RemotePlayerInfo playerInfo = mRemotePlayers.get(peerConnectionId);
        if (playerInfo == null) {
            return;
        }
        playerInfo.lastdate = receiveTime;
        if (iq.latency < MAX_LATENCY) {
            playerInfo.latency = iq.latency;
        }

        // The peer's player real position is now ahead of at least the latency.
        if (iq.control != Mode.STREAMING_STATUS_PAUSED) {
            playerInfo.position = iq.position + playerInfo.latency;
        } else {
            playerInfo.position = iq.position;
        }

        switch (iq.control) {
            case ASK_PAUSE_STREAMING:
                // Must be paused from the main UI thread.
                mHandler.post(this::pauseStreaming);
                break;

            case ASK_RESUME_STREAMING:
                // Must be resumed from the main UI thread.
                mHandler.post(this::resumeStreaming);
                break;

            case ASK_SEEK_STREAMING:
                // Must be seek from the main UI thread.
                mHandler.post(() -> seekStreaming(iq.length));
                break;

            case ASK_STOP_STREAMING:
                mCall.stopStreaming(true);
                break;

            case STREAMING_STATUS_PLAYING:
                playerInfo.paused = false;
                connection.updatePeerStreamingStatus(StreamingStatus.PLAYING);
                break;

            case STREAMING_STATUS_PAUSED:
                playerInfo.paused = true;
                connection.updatePeerStreamingStatus(StreamingStatus.PAUSED);
                break;

            case STREAMING_STATUS_ERROR:
                connection.updatePeerStreamingStatus(StreamingStatus.ERROR);
                break;

            case STREAMING_STATUS_UNSUPPORTED:
                connection.updatePeerStreamingStatus(StreamingStatus.UNSUPPORTED);
                break;

            case STREAMING_STATUS_READY:
            case STREAMING_STATUS_COMPLETED:
                playerInfo.paused = false;
                connection.updatePeerStreamingStatus(StreamingStatus.READY);
                break;

            default:
                break;
        }
    }

    /**
     * Handle the StreamingRequestIQ packet (Android >= 6.0).
     *
     * @param iq the streaming data iq.
     */
    public void onStreamingRequestIQ(@NonNull CallConnection connection, @NonNull StreamingRequestIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingRequestIQ: iq=" + iq);
        }

        if (iq.ident != mStreamIdent) {
            return;
        }

        final long receiveTime = System.currentTimeMillis();
        final UUID peerConnectionId = connection.getPeerConnectionId();
        if (peerConnectionId == null) {
            return;
        }

        // Record this player position and latency.
        final RemotePlayerInfo playerInfo = mRemotePlayers.get(peerConnectionId);
        if (playerInfo == null) {
            return;
        }
        playerInfo.lastdate = receiveTime;
        if (iq.lastRTT < MAX_LATENCY) {
            playerInfo.latency = iq.lastRTT;
        }
        if (playerInfo.paused) {
            playerInfo.position = iq.playerPosition;
        } else {
            playerInfo.position = iq.playerPosition + playerInfo.latency;
        }

        final StreamBuffer cachedBuffer = getBuffer(iq.offset);
        if (cachedBuffer != null) {
            final long now = System.currentTimeMillis();
            final long streamerPosition = getStreamerPosition(now);
            final int streamerLatency = (int) (now - receiveTime);
            final StreamingDataIQ responseIq = new StreamingDataIQ(IQ_STREAMING_DATA_SERIALIZER, iq.getRequestId(),
                    iq.ident, cachedBuffer.mFirstOffset, streamerPosition, iq.timestamp,
                    streamerLatency, cachedBuffer.mBuffer, 0, cachedBuffer.size());

            connection.sendMessage(responseIq, PeerConnectionService.StatType.IQ_SET_PUSH_OBJECT);
            return;
        }

        mExecutor.execute(() -> processRequest(iq.offset, (ErrorCode errorCode, StreamBuffer buffer) -> {
            final StreamingDataIQ responseIq;
            final long now = System.currentTimeMillis();
            final long streamerPosition = getStreamerPosition(now);
            final int streamerLatency = (int) (now - receiveTime);
            if (buffer != null) {
                responseIq = new StreamingDataIQ(IQ_STREAMING_DATA_SERIALIZER, iq.getRequestId(),
                        iq.ident, buffer.mFirstOffset, streamerPosition, iq.timestamp,
                        streamerLatency, buffer.mBuffer, 0, buffer.size());
            } else {
                responseIq = new StreamingDataIQ(IQ_STREAMING_DATA_SERIALIZER, iq.getRequestId(),
                        iq.ident, iq.offset, streamerPosition, iq.timestamp,
                        streamerLatency,null, 0, 0);
            }

            connection.sendMessage(responseIq, PeerConnectionService.StatType.IQ_SET_PUSH_OBJECT);
        }));
    }

    @SuppressLint("UnsafeOptInUsageError")
    void localStreamingRequest(long offset, StreamDataSource dataSource) {
        if (DEBUG) {
            Log.d(LOG_TAG, "localStreamingRequest: offset=" + offset);
        }

        mExecutor.execute(() -> {
            final StreamBuffer cachedBuffer = getBuffer(offset);
            if (cachedBuffer != null) {
                dataSource.write(offset, cachedBuffer.mFirstOffset, cachedBuffer.mBuffer);
                return;
            }
            processRequest(offset, (ErrorCode errorCode, StreamBuffer buffer) -> {
                if (buffer != null) {
                    dataSource.write(offset, buffer.mFirstOffset, buffer.mBuffer);
                } else {
                    dataSource.write(offset, offset, null);
                }
            });
        });
    }

    /**
     * Local player has changed its status.  If this is an error, stop the streaming.
     * We also post a StreamerEvent for the UI to update its state.
     *
     * @param mode the streaming status or request of our player.
     * @param offset the offset position.
     */
    void localPlayerStatus(@NonNull Mode mode, long offset) {
        if (DEBUG) {
            Log.d(LOG_TAG, "localPlayerStatus: mode=" + mode + " offset=" + offset);
        }

        final StreamingEvent event;
        boolean mustStop = false;
        switch (mode) {
            case STREAMING_STATUS_ERROR:
                event = StreamingEvent.EVENT_ERROR;
                mustStop = true;
                break;

            case STREAMING_STATUS_UNSUPPORTED:
                event = StreamingEvent.EVENT_UNSUPPORTED;
                mustStop = true;
                break;

            case STREAMING_STATUS_PLAYING:
                event = StreamingEvent.EVENT_PLAYING;
                break;

            case STREAMING_STATUS_PAUSED:
                event = StreamingEvent.EVENT_PAUSED;
                break;

            case STREAMING_STATUS_COMPLETED:
                event = StreamingEvent.EVENT_COMPLETED;
                break;

            case STREAMING_STATUS_STOPPED:
                event = StreamingEvent.EVENT_STOP;
                break;

            default:
                event = null;
                break;
        }

        if (event != null) {
            mCall.onEventStreaming(null, event);
        }
        if (mustStop) {
            mCall.stopStreaming(true);
        }
    }

    /**
     * Pause the streaming by sending a PAUSE_STREAMING message to each call participant.
     */
    private void doPauseStreaming() {
        if (DEBUG) {
            Log.d(LOG_TAG, "doPauseStreaming");
        }

        // Compute the max current position for all players and our local player.
        final long now = System.currentTimeMillis();
        final long streamerPos = getStreamerPosition(now);
        long maxPosition = streamerPos;
        int minLatency = Integer.MAX_VALUE;
        for (RemotePlayerInfo playerInfo : mRemotePlayers.values()) {
            if (playerInfo.lastdate > 0) {
                long position = playerInfo.getPosition(now);
                if (DEBUG) {
                    Log.d(LOG_TAG, "player position=" + playerInfo.position + " computed=" + position);
                }
                if (maxPosition < position) {
                    maxPosition = position;
                }
                if (playerInfo.latency > 0 && minLatency > playerInfo.latency) {
                    minLatency = playerInfo.latency;
                }
            }
        }
        if (minLatency > MAX_LATENCY) {
            minLatency = 0;
        }

        sendStreamControl(StreamingControlIQ.Mode.PAUSE_STREAMING, maxPosition, now, streamerPos);
        mCall.onEventStreaming(null, StreamingEvent.EVENT_PAUSED);

        // Check again the local player instance and pause it.
        final StreamPlayerImpl player = mLocalPlayer;
        if (player != null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "doPauseStreaming maxPos=" + maxPosition + " streamer=" + streamerPos);
            }
            long delay = maxPosition + minLatency - streamerPos;
            if (delay < 0) {
                delay = 0;
            }
            player.pause(delay);
        }
    }

    /**
     * Resume the streaming by sending a RESUME_STREAMING message to each call participant.
     */
    private void doResumeStreaming() {
        if (DEBUG) {
            Log.d(LOG_TAG, "doResumeStreaming");
        }

        // Compute the min current position for all players and our local player.
        // This indicates the time we have to wait for the player that is stopped too early.
        final long now = System.currentTimeMillis();
        final long streamerPos = getStreamerPosition(now);
        long minPosition = streamerPos;
        int minLatency = 0;
        for (RemotePlayerInfo playerInfo : mRemotePlayers.values()) {
            if (playerInfo.lastdate > 0) {
                long position = playerInfo.getPosition(now);
                if (DEBUG) {
                    Log.d(LOG_TAG, "player position=" + playerInfo.position + " computed=" + position);
                }
                if (minPosition > position) {
                    minPosition = position;
                }
                if (minLatency < playerInfo.latency) {
                    minLatency = playerInfo.latency;
                }
            }
        }

        sendStreamControl(StreamingControlIQ.Mode.RESUME_STREAMING, minPosition, now, streamerPos);
        mCall.onEventStreaming(null, StreamingEvent.EVENT_PLAYING);

        // Check again the local player instance and resume it.
        final StreamPlayerImpl player = mLocalPlayer;
        if (player != null) {
            if (DEBUG) {
                Log.e(LOG_TAG, "doResumeStreaming minPos=" + minPosition + " streamer=" + streamerPos);
            }
            long delay = streamerPos - minPosition + minLatency;
            if (delay < 0) {
                delay = 0;
            }
            player.resume(delay);
        }
    }

    private long getStreamerPosition(long now) {

        return mLocalPlayer == null ? 0 : mLocalPlayer.getCurrentPosition(now);
    }

    private void processRequest(long offset, Consumer<StreamBuffer> consumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "processRequest offset=" + offset);
        }

        final InputStream fileStream;
        synchronized (this) {
            fileStream = mFileStream;

        }

        if (fileStream == null) {
            consumer.onGet(ErrorCode.ITEM_NOT_FOUND, null);
            return;
        }

        while (!mEndOfStream && offset <= mFileStreamPos) {
            try {
                byte[] data = new byte[(int) StreamBuffer.BUFFER_SIZE];
                int size = fileStream.read(data);
                if (size > 0) {
                    StreamBuffer streamBuffer = new StreamBuffer(mFileStreamPos, data, size);
                    synchronized (this) {
                        mBuffers.add(streamBuffer);
                    }
                    mFileStreamPos = mFileStreamPos + size;
                } else {
                    mEndOfStream = true;
                }
            } catch (Exception exception) {
                Log.e(LOG_TAG, "Exception", exception);

                mEndOfStream = true;
                break;
            }
        }

        StreamBuffer buffer = getBuffer(offset);
        if (buffer != null) {
            consumer.onGet(ErrorCode.SUCCESS, buffer);
        } else {
            consumer.onGet(ErrorCode.ITEM_NOT_FOUND, null);
        }
    }

    /**
     * Get a stream buffer that contains the given offset position.
     *
     * @param offset the stream position.
     * @return the stream buffer or null if it must be loaded.
     */
    @Nullable
    synchronized StreamBuffer getBuffer(long offset) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getBuffer offset=" + offset);
        }

        return mBuffers.floor(new StreamBuffer(offset));
    }

    /**
     * Send a stream start IQ to each peer that is connected through the current call.
     *
     * @param length length of the content being streamed.
     */
    private void sendStreamStart(@NonNull ContentResolver resolver, long length) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendStreamStart length=" + length);
        }

        final StreamingControlIQ iq = new StreamingControlIQ(IQ_STREAMING_CONTROL_SERIALIZER , mCall.allocateRequestId(),
                mStreamIdent, mVideo ? StreamingControlIQ.Mode.START_VIDEO_STREAMING : StreamingControlIQ.Mode.START_AUDIO_STREAMING,
                length, System.currentTimeMillis(), 0, 0);

        final StreamingInfoIQ infoIQ;
        if (mMediaMetaData != null) {
            byte[] artwork = null;
            if (mMediaMetaData.artworkUri != null) {
                try {
                    Bitmap image = MediaStore.Images.Media.getBitmap(resolver, mMediaMetaData.artworkUri);
                    ImageTools imageTools = new AndroidImageTools();

                    artwork = imageTools.getImageData(image);
                } catch (Exception | OutOfMemoryError ignore) {

                }
            }
            infoIQ = new StreamingInfoIQ(IQ_STREAMING_INFO_SERIALIZER, mCall.allocateRequestId(), mStreamIdent,
                    mMediaMetaData.title, mMediaMetaData.album, mMediaMetaData.artist, artwork, mMediaMetaData.duration);
        } else {
            infoIQ = null;
        }
        final List<CallConnection> connections = mCall.getConnections();
        for (CallConnection connection : connections) {
            if (StreamingStatus.isSupported(connection.getStreamingStatus())) {
                final UUID peerConnectionId = connection.getPeerConnectionId();
                mRemotePlayers.put(peerConnectionId, new RemotePlayerInfo());
                connection.sendMessage(iq, PeerConnectionService.StatType.IQ_SET_PUSH_OBJECT);
                if (infoIQ != null) {
                    connection.sendMessage(infoIQ, PeerConnectionService.StatType.IQ_SET_PUSH_OBJECT);
                }
                connection.updatePeerStreamingStatus(StreamingStatus.READY);
            }
        }
    }

    /**
     * Send a stream control IQ to each peer that is connected through the current call.
     *
     * @param mode the control operation.
     * @param offset the control offset value.
     * @param timestamp the timestamp associated with the streamer position.
     * @param streamerPosition the streamer position.
     */
    private void sendStreamControl(@NonNull StreamingControlIQ.Mode mode, long offset, long timestamp, long streamerPosition) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendStreamControl mode=" + mode + " offset=" + offset);
        }

        final StreamingControlIQ iq = new StreamingControlIQ(IQ_STREAMING_CONTROL_SERIALIZER, mCall.allocateRequestId(),
                mStreamIdent, mode, offset, timestamp, streamerPosition, 0);

        final List<CallConnection> connections = mCall.getConnections();
        for (CallConnection connection : connections) {
            if (StreamingStatus.isSupported(connection.getStreamingStatus())) {
                connection.sendMessage(iq, PeerConnectionService.StatType.IQ_SET_RESET_CONVERSATION);
            }
        }
    }
}
