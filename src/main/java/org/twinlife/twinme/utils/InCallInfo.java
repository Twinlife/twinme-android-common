/*
 *  Copyright (c) 2020-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.graphics.Bitmap;
import android.graphics.Point;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.calls.CallStatus;

import java.util.UUID;

public class InCallInfo {

    @Nullable
    private UUID mContactId;

    @Nullable
    private final UUID mGroupId;
    @Nullable
    private final Bitmap mContactAvatar;
    @Nullable
    private Point mPosition;
    @NonNull
    private final CallStatus mCallStatus;

    private final boolean mIsVideoCall;

    public InCallInfo(@Nullable UUID contactId, @Nullable UUID groupId, @Nullable Bitmap contactAvatar, @Nullable Point position, @NonNull CallStatus callStatus, boolean isVideoCall) {

        mContactId = contactId;
        mGroupId = groupId;
        mContactAvatar = contactAvatar;
        mPosition = position;
        mCallStatus = callStatus;
        mIsVideoCall = isVideoCall;
    }

    public UUID getContactId() {

        return mContactId;
    }

    public void setContactId(UUID contactId) {

        mContactId = contactId;
    }

    public UUID getGroupId() {

        return mGroupId;
    }

    public Bitmap getContactAvatar() {

        return mContactAvatar;
    }

    public Point position() {

        return mPosition;
    }

    public void setPosition(Point position) {

        mPosition = position;
    }

    @NonNull
    public CallStatus getCallMode() {

        return mCallStatus;
    }

    public boolean isVideo() {

        return mIsVideoCall;
    }
}
