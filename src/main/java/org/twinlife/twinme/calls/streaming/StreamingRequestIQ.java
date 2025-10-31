/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * A request to ask a data block for a streaming content.
 *
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"4fab57a3-6c24-4318-b71d-22b60807cbc5",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"StreamingRequestIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"ident", "type":"long"},
 *     {"name":"offset", "type":"long"},
 *     {"name":"length", "type":"long"},
 *     {"name":"timestamp", "type":"long"},
 *     {"name":"playerPosition", "type":"long"},
 *     {"name":"lastRTT", "type":"int"}
 *  ]
 * }
 *
 * </pre>
 */
public class StreamingRequestIQ extends BinaryPacketIQ {
    private static final UUID STREAMING_REQUEST_SCHEMA_ID = UUID.fromString("4fab57a3-6c24-4318-b71d-22b60807cbc5");
    public static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_STREAMING_REQUEST_SERIALIZER = StreamingRequestIQ.createSerializer(STREAMING_REQUEST_SCHEMA_ID, 1);

    final long ident;
    final long offset;
    final long length;
    final long timestamp;
    final long playerPosition;
    final int lastRTT;

    public StreamingRequestIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId,
                       long ident, long offset, long length, long playerPosition, long timestamp, int lastRTT) {

        super(serializer, requestId);

        this.ident = ident;
        this.offset = offset;
        this.length = length;
        this.timestamp = timestamp;
        this.playerPosition = playerPosition;
        this.lastRTT = lastRTT;
    }

    //
    // Override Object methods
    //

    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        if (BuildConfig.DEBUG) {
            super.appendTo(stringBuilder);

            stringBuilder.append(" ident=");
            stringBuilder.append(ident);
            stringBuilder.append(" offset=");
            stringBuilder.append(offset);
            stringBuilder.append(" length=");
            stringBuilder.append(length);
            stringBuilder.append(" playerPosition=");
            stringBuilder.append(playerPosition);
            stringBuilder.append(" lastRTT=");
            stringBuilder.append(lastRTT);
            stringBuilder.append(" timestamp=");
            stringBuilder.append(timestamp);
        }
    }

    @NonNull
    public String toString() {

        final StringBuilder stringBuilder = new StringBuilder();
        if (BuildConfig.DEBUG) {
            stringBuilder.append("StreamingRequestIQ[");
            appendTo(stringBuilder);
            stringBuilder.append("]");
        }
        return stringBuilder.toString();
    }

    private static class StreamingRequestIQSerializer extends BinaryPacketIQSerializer {

        StreamingRequestIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, StreamingRequestIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            final StreamingRequestIQ streamingRequestIQ = (StreamingRequestIQ) object;
            encoder.writeLong(streamingRequestIQ.ident);
            encoder.writeLong(streamingRequestIQ.offset);
            encoder.writeLong(streamingRequestIQ.length);
            encoder.writeLong(streamingRequestIQ.timestamp);
            encoder.writeLong(streamingRequestIQ.playerPosition);
            encoder.writeInt(streamingRequestIQ.lastRTT);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            final BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            final long ident = decoder.readLong();
            final long offset = decoder.readLong();
            final long length = decoder.readLong();
            final long timestamp = decoder.readLong();
            final long playerPosition = decoder.readLong();
            final int lastRTT = decoder.readInt();

            return new StreamingRequestIQ(this, serviceRequestIQ.getRequestId(), ident, offset, length,
                    playerPosition, timestamp, lastRTT);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new StreamingRequestIQSerializer(schemaId, schemaVersion);
    }
}
