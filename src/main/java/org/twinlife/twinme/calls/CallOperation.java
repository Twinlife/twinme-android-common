/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;

/**
 * Describes an internal operation made for handling Call-level operations.
 */
final class CallOperation {

    static final int START_CALL = 1 << 1;
    static final int START_CALL_DONE = 1 << 2;
    static final int ACCEPTED_CALL = 1 << 8;
    static final int ACCEPTED_CALL_DONE = 1 << 9;
    static final int TERMINATE_CALL = 1 << 10;
    static final int TERMINATE_CALL_DONE = 1 << 11;

    @NonNull
    final CallState call;
    final int operation;

    CallOperation(@NonNull CallState call, int operation) {

        this.call = call;
        this.operation = operation;
    }
}
