/*
 *  Copyright (c) 2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * Participant info IQ sent to a call group member to share the participant name and picture.
 *
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"a8aa7e0d-c495-4565-89bb-0c5462b54dd0",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"ParticipantInfoIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"memberId", "type":"String"},
 *     {"name":"name", "type":"String"},
 *     {"name":"description", [null, "type":"String"}],
 *     {"name":"avatar", [null, "type":"bytes"]}
 *  ]
 * }
 *
 * </pre>
 */
class ParticipantInfoIQ extends BinaryPacketIQ {

    private static class ParticipantInfoIQSerializer extends BinaryPacketIQSerializer {

        ParticipantInfoIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, ParticipantInfoIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            ParticipantInfoIQ participantInfoIQ = (ParticipantInfoIQ) object;
            encoder.writeString(participantInfoIQ.memberId);
            encoder.writeString(participantInfoIQ.name);
            encoder.writeOptionalString(participantInfoIQ.description);
            encoder.writeOptionalBytes(participantInfoIQ.thumbnailData);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            String memberId = decoder.readString();
            String name = decoder.readString();
            String description = decoder.readOptionalString();
            byte[] thumbnailData = decoder.readOptionalBytes(null);

            return new ParticipantInfoIQ(this, serviceRequestIQ.getRequestId(), memberId, name, description, thumbnailData);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new ParticipantInfoIQSerializer(schemaId, schemaVersion);
    }

    @NonNull
    final String memberId;
    @NonNull
    final String name;
    @Nullable
    final String description;
    @Nullable
    final byte[] thumbnailData;

    //
    // Override Object methods
    //

    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        super.appendTo(stringBuilder);

        stringBuilder.append(" memberId=");
        stringBuilder.append(memberId);
        stringBuilder.append(" name=");
        stringBuilder.append(name);
        stringBuilder.append(" description=");
        stringBuilder.append(description);
    }

    @NonNull
    public String toString() {

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("ParticipantInfoIQ[");
        appendTo(stringBuilder);
        stringBuilder.append("]");

        return stringBuilder.toString();
    }

    ParticipantInfoIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId,
                      @NonNull String memberId, @NonNull String name, @Nullable String description, @Nullable byte[] thumbnailData) {

        super(serializer, requestId);

        this.memberId = memberId;
        this.name = name;
        this.description = description;
        this.thumbnailData = thumbnailData;
    }
}
