/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.services;

import org.twinlife.twinlife.AssertPoint;

public enum ServiceAssertPoint implements AssertPoint {
    ON_ERROR(true),
    GET_INVITATION_CODE,
    DELETE_INVITATION_CODE,
    NULL_SUBJECT,
    INVALID_ID,
    INVALID_TWINCODE,
    GET_IDENTITY_IMAGE(true),
    MAIN_THREAD(true);

    public int getIdentifier() {

        return this.ordinal() + BASE_VALUE;
    }

    public boolean stackTrace() {

        return mStackTrace;
    }

    ServiceAssertPoint() {
        mStackTrace = false;
    }

    ServiceAssertPoint(boolean stackTrace) {
        mStackTrace = stackTrace;
    }

    private final boolean mStackTrace;

    private static final int BASE_VALUE = 4000;
}