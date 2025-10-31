/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.Nullable;
import org.twinlife.twinme.utils.MediaMetaData;

/**
 * A media stream that is played during an Audio/Video call.  The media stream is either
 * obtained locally if we are streaming a content or received through the WebRTC data channel.
 *
 * A `StreamingEvent` is posted each time the stream player changes it state and it can be
 * received with the `CallParticipantObserver.onEventStreaming`.
 */
public interface StreamPlayer {

    @Nullable
    MediaMetaData getMediaInfo();

    @Nullable
    Streamer getStreamer();

    /**
     * Check if the media stream is a video stream.
     *
     * @return true if the stream is a video stream.
     */
    boolean isVideo();

    /**
     * Check if the current player is in pause
     *
     * @return true if the player is in pause.
     */
    boolean isPause();

    /**
     * Get the current player position in milliseconds.
     *
     * @param now the current timestamp.
     * @return the current player position.
     */
    long getCurrentPosition(long now);

    void askPause();

    void askResume();

    void askSeek(long offset);

    void askStop();
}
