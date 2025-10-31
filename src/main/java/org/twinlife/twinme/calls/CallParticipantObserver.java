/*
 *  Copyright (c) 2022-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.GeolocationDescriptor;
import org.twinlife.twinme.calls.streaming.StreamingEvent;

import java.util.List;

/**
 * Observer interface to be informed when a new call participant arrives, leaves or is updated.
 */
public interface CallParticipantObserver {

    /**
     * A new participant is added to the call group.
     *
     * @param participant the participant.
     */
    void onAddParticipant(@NonNull CallParticipant participant);

    /**
     * One or several participants are removed from the call.
     *
     * @param participants the list of participants being removed.
     */
    void onRemoveParticipants(@NonNull List<CallParticipant> participants);

    /**
     * An event occurred for the participant and its state was changed.
     *
     * @param participant the participant.
     * @param event the event that occurred.
     */
    void onEventParticipant(@NonNull CallParticipant participant, @NonNull CallParticipantEvent event);

    /**
     * A streaming event occurred.  When the streamerParticipant is null, the event is associated with
     * the local player for the streamed content we are sending.  Otherwise, it is associated with the
     * player for a streaming sent by the participant.
     *
     * @param streamerParticipant the participant that is streaming the content (null if local streamer).
     * @param event the streaming event.
     */
    void onEventStreaming(@Nullable CallParticipant streamerParticipant, @NonNull StreamingEvent event);

    /**
     * The participant has sent us a descriptor.
     *
     * @param participant the participant.
     * @param descriptor the descriptor that was sent.
     */
    void onPopDescriptor(@Nullable CallParticipant participant, @NonNull Descriptor descriptor);

    /**
     * The participant has updated its geolocation.
     *
     * @param participant the participant.
     * @param descriptor the geolocation descriptor being updated.
     */
    void onUpdateGeolocation(@Nullable CallParticipant participant, @NonNull GeolocationDescriptor descriptor);

    /**
     * The participant has deleted its descriptor.
     *
     * @param participant the participant.
     * @param descriptorId the descriptor that was deleted.
     */
    void onDeleteDescriptor(@Nullable CallParticipant participant, @NonNull DescriptorId descriptorId);
}
