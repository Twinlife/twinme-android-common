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

import androidx.annotation.NonNull;

/**
 * Describes an internal operation made for making a P2P call connection.
 */
final class ConnectionOperation {
    static final int GET_CONTACT = 1;
    static final int CREATE_OUTGOING_PEER_CONNECTION = 1 << 4;
    static final int CREATE_OUTGOING_PEER_CONNECTION_DONE = 1 << 5;
    static final int CREATE_INCOMING_PEER_CONNECTION = 1 << 6;
    static final int CREATE_INCOMING_PEER_CONNECTION_DONE = 1 << 7;
    static final int CREATED_PEER_CONNECTION = 1 << 9;
    static final int CREATE_CALL_ROOM = 1 << 12;
    static final int CREATE_CALL_ROOM_DONE = 1 << 13;
    static final int JOIN_CALL_ROOM = 1 << 14;
    static final int JOIN_CALL_ROOM_DONE = 1 << 15;
    static final int INVITE_CALL_ROOM = 1 << 16;
    static final int INVITE_CALL_ROOM_DONE = 1 << 17;

    @NonNull
    final CallState call;
    @NonNull
    final CallConnection callConnection;
    final int operation;

    ConnectionOperation(@NonNull CallConnection callConnection, int operation) {

        this.callConnection = callConnection;
        this.call = callConnection.getCall();
        this.operation = operation;
    }
}
