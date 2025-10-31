/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls;

import org.twinlife.twinlife.AssertPoint;

public enum CallAssertPoint implements AssertPoint {
    START_CALL,
    ON_ERROR,
    START_FOREGROUND_FAILURE(true),
    ADD_NEW_INCOMING_CALL,
    PLACE_CALL,
    GET_IDENTITY_IMAGE(true),
    NO_NOTIFICATION_INFO;

    public int getIdentifier() {

        return this.ordinal() + BASE_VALUE;
    }

    public boolean stackTrace() {

        return mStackTrace;
    }

    CallAssertPoint() {
        mStackTrace = false;
    }

    CallAssertPoint(boolean stackTrace) {
        mStackTrace = stackTrace;
    }

    private final boolean mStackTrace;

    private static final int BASE_VALUE = 4100;
}