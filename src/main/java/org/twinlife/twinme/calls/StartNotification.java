/*
 *  Copyright (c) 2022-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import android.content.Intent;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.models.Originator;

import java.util.UUID;

final class StartNotification {
    @NonNull
    final UUID peerConnectionId;
    @NonNull
    final CallStatus callStatus;
    @NonNull
    final Originator originator;
    @Nullable
    final Bitmap avatar;

    @Nullable
    Intent startCallServiceIntent = null;

    StartNotification(@NonNull CallStatus callStatus, @NonNull UUID peerConnectionId, @NonNull Originator originator, @Nullable Bitmap avatar) {
        this.callStatus = callStatus;
        this.peerConnectionId = peerConnectionId;
        this.originator = originator;
        this.avatar = avatar;
    }
}
