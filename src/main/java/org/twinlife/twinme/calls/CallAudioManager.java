/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.audio.AudioDevice;

import java.util.Set;

public interface CallAudioManager {
    @NonNull
    AudioDevice getSelectedAudioDevice();

    @Nullable
    String getBluetoothDeviceName();

    @NonNull
    Set<AudioDevice> getAudioDevices();

    boolean isHeadsetAvailable();

    void setCommunicationDevice(@NonNull AudioDevice audioDevice);

    void setMicrophoneMute(boolean mute);
}
