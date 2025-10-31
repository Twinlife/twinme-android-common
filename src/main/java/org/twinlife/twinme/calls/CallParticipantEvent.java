/*
 *  Copyright (c) 2022-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls;

/**
 * An event that informs about a change on the call participant.
 */
public enum CallParticipantEvent {
    EVENT_CONNECTED,
    EVENT_IDENTITY,
    EVENT_AUDIO_ON,
    EVENT_AUDIO_OFF,
    EVENT_VIDEO_ON,
    EVENT_VIDEO_OFF,
    EVENT_RINGING,
    EVENT_STREAM_START,    // Participant started to stream some content
    EVENT_STREAM_INFO,     // Received information for streamed content
    EVENT_STREAM_STOP,     // Streaming is stopped
    EVENT_STREAM_PAUSE,    // Streaming is paused
    EVENT_STREAM_RESUME,   // Streaming is resumed
    EVENT_STREAM_STATUS,   // Participant streaming status is updated
    EVENT_HOLD,            // Participant has put the call on hold
    EVENT_RESUME,          // Participant has resumed the call
    EVENT_KEY_CHECK_INITIATE, // Participant has started a key check
    EVENT_ON_KEY_CHECK_INITIATE, // Participant has answered our key check request
    EVENT_CURRENT_WORD_CHANGED, // Participant (and us) have checked a word, so the current word has changed
    EVENT_WORD_CHECK_RESULT_KO, // Participant's current word is incorrect
    EVENT_TERMINATE_KEY_CHECK, // Participant and us have both finished the key check

    EVENT_SCREEN_SHARING_ON,   // Participant started to share its screen or window
    EVENT_SCREEN_SHARING_OFF,   // Participant stopped the sharing

    EVENT_ASK_CAMERA_CONTROL,     // The remote participant is asking to take control of the camera
    EVENT_CAMERA_CONTROL_DENIED,  // The camera control is denied.
    EVENT_CAMERA_CONTROL_GRANTED, // The peer grant access to its camera.
    EVENT_CAMERA_CONTROL_DONE     // The camera control by the peer is stopped.
}
