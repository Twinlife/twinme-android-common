/*
 *  Copyright (c) 2019-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.audio;

/**
 * AudioDevice is the names of possible audio devices that we currently support.
 */
public enum AudioDevice {
    SPEAKER_PHONE,
    WIRED_HEADSET,
    EARPIECE,
    BLUETOOTH,
    NONE
}
