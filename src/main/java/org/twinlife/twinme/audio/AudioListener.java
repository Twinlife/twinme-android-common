/*
 *  Copyright (c) 2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.audio;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

import java.util.Set;

/**
 * Listener to be notified about audio device events.
 */
public interface AudioListener {

    /**
     * Called when a media button is pressed.
     *
     * @param keyEvent the media button being pressed.
     */
    void onMediaButton(@NonNull KeyEvent keyEvent);

    /**
     * Notify a change of audio device.
     *
     * @param selectedAudioDevice the current audio device.
     * @param availableAudioDevices the list of available audio devices.
     */
    void onAudioDeviceChanged(@NonNull AudioDevice selectedAudioDevice, @NonNull Set<AudioDevice> availableAudioDevices);

    void onPlayerReady();
}
