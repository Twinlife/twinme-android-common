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
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * Participant transfer IQ sent to a call group member to indicate that a transfer is taking place.
 * Upon reception, the member which sent this IQ will be replaced by the member whose ID is found in the payload.
 *
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"800fd629-83c4-4d42-8910-1b4256d19eb8",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"ParticipantTransferIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"memberId", "type":"String"}
 *  ]
 * }
 *
 * </pre>
 */
class ParticipantTransferIQ extends BinaryPacketIQ {

    private static class ParticipantTransferIQSerializer extends BinaryPacketIQSerializer {

        ParticipantTransferIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, ParticipantTransferIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            ParticipantTransferIQ participantInfoIQ = (ParticipantTransferIQ) object;
            encoder.writeString(participantInfoIQ.memberId);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            String memberId = decoder.readString();

            return new ParticipantTransferIQ(this, serviceRequestIQ.getRequestId(), memberId);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new ParticipantTransferIQSerializer(schemaId, schemaVersion);
    }

    @NonNull
    final String memberId;

    //
    // Override Object methods
    //

    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        super.appendTo(stringBuilder);

        stringBuilder.append(" memberId=");
        stringBuilder.append(memberId);
    }

    @NonNull
    public String toString() {

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("ParticipantTransferIQ[");
        appendTo(stringBuilder);
        stringBuilder.append("]");

        return stringBuilder.toString();
    }

    ParticipantTransferIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull String memberId) {

        super(serializer, requestId);

        this.memberId = memberId;
    }
}
