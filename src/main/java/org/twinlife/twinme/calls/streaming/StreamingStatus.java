/*
 *  Copyright (c) 2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.Nullable;

/**
 * Status of streaming for a participant.
 */
public enum StreamingStatus {
    UNKNOWN,        // Status is not known (peer is not yet connected)
    NOT_AVAILABLE,  // Peer does not support streaming
    READY,          // Peer is ready to receive streaming
    PLAYING,        // Peer is playing the stream we are sending
    PAUSED,         // Peer's player is paused
    UNSUPPORTED,    // Peer does not support the media we are sending
    ERROR;          // Other error reported by the peer

    public static boolean isSupported(@Nullable StreamingStatus status) {

        return status != UNKNOWN && status != NOT_AVAILABLE;
    }
}
