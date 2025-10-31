/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import org.twinlife.twinlife.PeerConnectionService;
import org.twinlife.twinme.calls.CallConnection;
import org.twinlife.twinme.calls.CallState;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.twinlife.twinme.calls.streaming.StreamingRequestIQ.IQ_STREAMING_REQUEST_SERIALIZER;

/**
 * ExoPlayer datasource to get the media content from the Web-RTC data channel through the StreamRequestIQ
 * and StreamDataIQ.
 */
@SuppressWarnings("ManualMinMaxCalculation") // Never use Math.min()
@UnstableApi
final class StreamDataSource extends BaseDataSource {
    private static final String LOG_TAG = "StreamDataSource";
    private static final boolean DEBUG = false;

    private static final int MAX_RTT_TIME = 10000; // 10s

    // ExoPlayer has its own buffering, keep a small buffer on our side.
    private static final int MAX_BUFFERS = 3;

    @NonNull
    private final CallState mCall;
    @Nullable
    private final CallConnection mConnection;
    @Nullable
    private final StreamerImpl mLocalStreamer;
    private final long mStreamIdent;
    private final ArrayBlockingQueue<StreamBuffer> mQueue;
    private final StreamPlayerImpl mPlayer;
    @Nullable
    private StreamBuffer mCurrent;
    private long mReadPosition;
    private long mStreamPosition;
    private volatile boolean mEndOfStream;
    private Uri mUri;
    private int mLastRTT;
    private long mLastStreamerPosition;
    private long mLastStreamerPositionTime;
    private boolean mOpened;

    StreamDataSource(long ident, @NonNull CallState call,
                     @Nullable CallConnection connection,
                     @Nullable StreamerImpl localStreamer,
                     @NonNull StreamPlayerImpl player) {
        super(true);

        mStreamIdent = ident;
        mCall = call;
        mConnection = connection;
        mLocalStreamer = localStreamer;
        mPlayer = player;
        mQueue = new ArrayBlockingQueue<>(MAX_BUFFERS);
        mCurrent = null;
        mLastRTT = 0;
        mLastStreamerPosition = 0;
        mLastStreamerPositionTime = 0;
        mEndOfStream = false;
        mReadPosition = 0;
        mStreamPosition = 0;
        requestFillBuffers();
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) {
        if (DEBUG) {
            Log.d(LOG_TAG, "open: dataSpec=" + dataSpec);
        }

        mOpened = true;
        mUri = dataSpec.uri;
        mReadPosition = 0;
        mEndOfStream = false;
        mCurrent = null;
        mQueue.clear();
        transferInitializing(dataSpec);
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    @Nullable
    public Uri getUri() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getUri");
        }

        return mUri;
    }

    @Override
    public void close() {
        if (DEBUG) {
            Log.d(LOG_TAG, "close");
        }

        mCurrent = null;
        mQueue.clear();
        if (mOpened) {
            mOpened = false;
            transferEnded();
        }
        mUri = null;
    }

    /**
     * Reads up to {@code length} bytes of data from the input.
     *
     * <p>If {@code readLength} is zero then 0 is returned. Otherwise, if no data is available because
     * the end of the opened range has been reached, then {@link C#RESULT_END_OF_INPUT} is returned.
     * Otherwise, the call will block until at least one byte of data has been read and the number of
     * bytes read is returned.
     *
     * @param buffer A target array into which data should be written.
     * @param offset The offset into the target array at which to write.
     * @param length The maximum number of bytes to read from the input.
     * @return The number of bytes read, or {@link C#RESULT_END_OF_INPUT} if the input has ended. This
     *     may be less than {@code length} because the end of the input (or available data) was
     *     reached, the method was interrupted, or the operation was aborted early for another reason.
     */
    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) {

        if (length == 0) {
            return 0;
        }
        int len = readAt(buffer, offset, length);
        if (len > 0) {
            requestFillBuffers();
        }
        return len;
    }

    /**
     * Get the guessed latency of the datasource.
     *
     * @return the datasource latency.
     */
    int getLatency() {

        return mLastRTT / 2;
    }

    /**
     * Handle the StreamingDataIQ packet.
     *
     * @param iq the streaming data iq.
     */
    void onStreamingDataIQ(@NonNull StreamingDataIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingDataIQ: iq=" + iq);
        }

        if (iq.ident != mStreamIdent) {
            return;
        }

        // Compute RTT for the StreamingRequestDataIQ+StreamingDataIQ
        final long now = System.currentTimeMillis();
        final long requestTime = now - iq.timestamp;
        if (requestTime > 0 && requestTime - (long)iq.streamerLatency < MAX_RTT_TIME) {
            mLastRTT = (int) requestTime - iq.streamerLatency;
        }

        // Keep the player position on the streamer side.
        mLastStreamerPosition = iq.streamerPosition + (mLastRTT / 2);
        mLastStreamerPositionTime = now;
        write(iq.offset, iq.offset, iq.data);
    }

    /**
     * Receive a block of data from the peer.
     *
     * @param offset the offset position for the block.
     * @param data the block of data.
     */
    void write(long requestOffset, long offset, @Nullable byte[] data) {
        if (DEBUG) {
            Log.d(LOG_TAG, "write offset=" + offset);
        }

        final long length = data == null || requestOffset != offset ? 0 : data.length;
        final StreamBuffer buffer;
        if (length > 0) {
            buffer = new StreamBuffer(offset, data);
        } else {
            buffer = null;
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "write add buffer=" + buffer);
        }

        mEndOfStream = length == 0;
        if (!mQueue.offer(buffer)) {
            Log.e(LOG_TAG, "Buffer insert failed for buffer=" + buffer);
        }
    }

    /**
     * Send a request to get a stream block.
     *
     * @param ident the stream identification.
     * @param offset the stream block offset.
     */
    private void sendStreamRequest(long ident, long offset) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendStreamRequest ident=" + ident + " offset=" + offset);
        }

        final StreamPlayerImpl player = mPlayer;
        if (mConnection != null && player != null) {
            final long now = System.currentTimeMillis();
            final long playerPosition = player.getCurrentPosition(now);
            final long requestId = mCall.allocateRequestId();

            if (DEBUG) {
                final long deltaPosition;
                final long dt = now - mLastStreamerPositionTime;
                if (mLastStreamerPositionTime > 0) {
                    deltaPosition = playerPosition - (mLastStreamerPosition + dt);
                } else {
                    deltaPosition = 0;
                }

                Log.d(LOG_TAG, "sendStreamRequest requestId=" + requestId + " offset=" + offset
                        + " currentPos=" + playerPosition + " streamerPos=" + mLastStreamerPosition
                        + " dt=" + dt + " player-streamer offset=" + deltaPosition
                        + " readPos=" + mReadPosition + " queue=" + mQueue.size());
            }

            final StreamingRequestIQ iq = new StreamingRequestIQ(IQ_STREAMING_REQUEST_SERIALIZER, requestId,
                    ident, offset, StreamBuffer.BUFFER_SIZE, playerPosition, now, mLastRTT);
            mConnection.sendMessage(iq, PeerConnectionService.StatType.IQ_SET_PUSH_OBJECT);
        } else if (mLocalStreamer != null) {
            mLocalStreamer.localStreamingRequest(offset, this);
        }
    }

    /**
     * Look at the buffer queue and request more before the ExoPlayer tries to read something.
     */
    private void requestFillBuffers() {
        if (DEBUG) {
            Log.d(LOG_TAG, "requestFillBuffers");
        }

        // Make sure that we have enough data for next reads and ask for more buffer to the peer.
        if (!mEndOfStream) {
            int remain = mQueue.remainingCapacity() - 1;
            long pendingRead = mReadPosition - mStreamPosition;
            remain -= (int) (pendingRead + StreamBuffer.BUFFER_SIZE - 1) / StreamBuffer.BUFFER_SIZE;
            int transfered = 0;
            while (remain > 0) {
                sendStreamRequest(mStreamIdent, mReadPosition);
                remain--;
                mReadPosition += StreamBuffer.BUFFER_SIZE;
                transfered += StreamBuffer.BUFFER_SIZE;
            }
            if (transfered > 0) {
                bytesTransferred(transfered);
            }
        }
    }

    /**
     * Read the stream at the given position.
     *
     * @param buffer the buffer
     * @param offset the offset within the buffer
     * @param size the size to read
     * @return the size that was read.
     */
    private int readAt(byte[] buffer, int offset, int size) {
        if (DEBUG) {
            Log.d(LOG_TAG, "readAt position=" + mReadPosition + " offset=" + offset + " size=" + size);
        }

        int result = 0;
        while (size > 0) {
            StreamBuffer first = mCurrent;
            if (first == null) {
                first = mQueue.poll();
                mCurrent = first;
            }
            if (DEBUG) {
                Log.d(LOG_TAG, "read position=" + mStreamPosition + " offset=" + offset + " size=" + size + " buffer=" + first);
            }
            if (first != null) {
                final int pos = (int) (mStreamPosition - first.mFirstOffset);
                final int avail = (int) (first.mLastOffset - mStreamPosition);
                if (avail > 0 && pos >= 0) {

                    // Read as many bytes are we can, and stop when we have read all the content.
                    int copySize;
                    if (avail >= size) {
                        copySize = size;
                    } else {
                        copySize = avail;
                    }

                    // Log.e(LOG_TAG, "copy " + copySize + " from " + pos + " global pos " + position);
                    System.arraycopy(first.mBuffer, pos, buffer, offset, copySize);
                    result += copySize;
                    size -= copySize;
                    mStreamPosition += copySize;
                    if (size == 0) {
                        return result;
                    }

                    offset += copySize;
                } else if (pos < 0) {
                    Log.e(LOG_TAG, "Invalid pos=" + pos + " avail=" + avail + " buffer=" + first);
                }

                // Get the next block from the queue.
                first = mCurrent = mQueue.poll();
            }

            if (mEndOfStream || !mOpened) {
                if (result > 0) {
                    return result;
                } else {
                    return -1;
                }
            }

            if (first == null) {
                // Before waiting for a buffer, ask to fill some new buffers if needed.
                requestFillBuffers();
                try {
                    mCurrent = mQueue.poll(10, TimeUnit.SECONDS);

                    // If we don't get a buffer within the 10s timeslot, consider we reached end of stream.
                    if (mCurrent == null) {
                        mEndOfStream = true;
                    }
                } catch (InterruptedException exception) {
                   Log.d(LOG_TAG, "Exception: ", exception);

                }
            }
        }

        return result;
    }
}
