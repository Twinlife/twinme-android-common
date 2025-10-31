/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.Nullable;

/**
 * The streamer is responsible for sending a media stream to every peer connection/participant to a call.
 *
 * - streaming is started by sending the `startStreaming` intent on the `CallService`,
 * - while streaming a `StreamingEvent` is posted to notify about streamer state change (play, pause, complete, stop)
 *   (see CallParticipantObserver.onEventStreaming),
 * - streaming must be stopped by sending the `stopStreaming` intent on the `CallService`.
 */
public interface Streamer {

    /**
     * Check if the media stream is a video stream.
     *
     * @return true if the stream is a video stream.
     */
    boolean isVideo();

    /**
     * Get local player
     *
     */
    @Nullable
    StreamPlayer getPlayer();

    /**
     * Resume the streaming by sending a RESUME_STREAMING message to each call participant.
     */
    void resumeStreaming();

    /**
     * Pause the streaming by sending a PAUSE_STREAMING message to each call participant.
     */
    void pauseStreaming();
}
