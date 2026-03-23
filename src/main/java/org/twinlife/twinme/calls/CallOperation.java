/*
 *  Copyright (c) 2024-2026 twinlife SA.
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

    static final int WAIT_CONFERENCE = 1;
    static final int START_CALL = 1 << 1;
    static final int START_CALL_DONE = 1 << 2;
    static final int ACCEPTED_CALL = 1 << 3;
    static final int ACCEPTED_CALL_DONE = 1 << 4;
    static final int TERMINATE_CALL = 1 << 5;
    static final int TERMINATE_CALL_DONE = 1 << 6;
    static final int CREATE_CALL_ROOM = 1 << 7;
    static final int CREATE_CALL_ROOM_DONE = 1 << 8;
    static final int JOIN_CONFERENCE = 1 << 9;
    static final int JOIN_CONFERENCE_DONE = 1 << 10;
    static final int JOIN_CALL_ROOM = 1 << 11;
    static final int INVITE_CALL_ROOM = 1 << 12;
    static final int CREATE_INCOMING_PEER_CONNECTION_DONE = 1 << 13;

    @NonNull
    final CallState call;
    final int operation;

    CallOperation(@NonNull CallState call, int operation) {

        this.call = call;
        this.operation = operation;
    }
}
