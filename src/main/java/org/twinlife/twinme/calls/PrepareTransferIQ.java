/*
 *  Copyright (c) 2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * IQ sent by the transferred device to the other device(s) of the call
 * to indicate that the transfer is beginning. The other device will then wait for
 * the ParticipantTransferIQ before establishing the P2P connection.
 * <p>
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"9eaa4ad1-3404-4bcc-875d-dc75c748e188",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"PrepareTransferIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 * }
 *
 * </pre>
 */
class PrepareTransferIQ extends BinaryPacketIQ {

    private static class PrepareTransferIQSerializer extends BinaryPacketIQSerializer {

        PrepareTransferIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, PrepareTransferIQ.class);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            return new PrepareTransferIQ(this, serviceRequestIQ.getRequestId());
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new PrepareTransferIQSerializer(schemaId, schemaVersion);
    }

    PrepareTransferIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId) {

        super(serializer, requestId);
    }
}
