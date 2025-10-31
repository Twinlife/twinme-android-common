/*
 *  Copyright (c) 2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

/**
 * Event posted to notify a change in the streaming.
 */
public enum StreamingEvent {
    // A streaming has started.
    EVENT_START,

    // Player associated with streaming is now playing.
    EVENT_PLAYING,

    // Player is now paused.
    EVENT_PAUSED,

    // Player associated with streaming has completed playing.
    EVENT_COMPLETED,

    // Player does not support the local streamed content.
    EVENT_UNSUPPORTED,

    // Player had errors while playing streamed content.
    EVENT_ERROR,

    // The streaming has stopped.
    EVENT_STOP
}
