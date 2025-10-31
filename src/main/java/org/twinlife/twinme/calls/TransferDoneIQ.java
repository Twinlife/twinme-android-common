/*
 *  Copyright (c) 2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.util.BinaryPacketIQ;

/**
 * IQ sent by the transfer target (browser) to the transferred participant
 * once the connection with the other participant has been established.
 * At this point the transferred participant knows they can terminate the call.
 * <p>
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"641bf1f6-ebbf-4501-9151-76abc1b9adad",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"TransferDoneIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 * }
 *
 * </pre>
 */
class TransferDoneIQ extends BinaryPacketIQ {

    TransferDoneIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId) {

        super(serializer, requestId);
    }
}
