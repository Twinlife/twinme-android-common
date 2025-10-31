/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * A data block for the streaming flow in response to a streaming request.
 *
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"5a5d0994-2ca3-4a62-9da3-9b7d5c4abdd4",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"StreamingDataIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"ident", "type":"long"},
 *     {"name":"offset", "type":"long"},
 *     {"name":"timestamp", "type":"long"},
 *     {"name":"streamerPosition", "type":"long"},
 *     {"name":"streamerLatency", "type":"int"},
 *     {"name":"data", "type": [null, "bytes"]}
 *  ]
 * }
 *
 * </pre>
 *
 * @see StreamingRequestIQ
 */
public class StreamingDataIQ extends BinaryPacketIQ {
    private static final UUID STREAMING_DATA_SCHEMA_ID = UUID.fromString("5a5d0994-2ca3-4a62-9da3-9b7d5c4abdd4");
    public static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_STREAMING_DATA_SERIALIZER = StreamingDataIQ.createSerializer(STREAMING_DATA_SCHEMA_ID, 1);

    final long ident;
    final long offset;
    final long timestamp;
    final long streamerPosition;
    final int streamerLatency;
    @Nullable
    final byte[] data;
    final int size;
    final int startPos;

    public StreamingDataIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId,
                           long ident, long offset, long streamerPosition, long timestamp, int streamerLatency,
                           @Nullable byte[] data, int startPos, int length) {

        super(serializer, requestId);

        this.ident = ident;
        this.offset = offset;
        this.streamerPosition = streamerPosition;
        this.timestamp = timestamp;
        this.streamerLatency = streamerLatency;
        this.data = data;
        this.startPos = startPos;
        this.size = length;
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
            stringBuilder.append(size);
            stringBuilder.append(" streamerPosition=");
            stringBuilder.append(streamerPosition);
            stringBuilder.append(" streamerLatency=");
            stringBuilder.append(streamerLatency);
            stringBuilder.append(" timestamp=");
            stringBuilder.append(timestamp);
        }
    }

    @NonNull
    public String toString() {

        final StringBuilder stringBuilder = new StringBuilder();
        if (BuildConfig.DEBUG) {
            stringBuilder.append("StreamingDataIQ[");
            appendTo(stringBuilder);
            stringBuilder.append("]");
        }
        return stringBuilder.toString();
    }

    protected int getBufferSize() {

        if (data != null) {
            return size + SERIALIZER_BUFFER_DEFAULT_SIZE;
        } else {
            return SERIALIZER_BUFFER_DEFAULT_SIZE;
        }
    }

    private static class StreamingDataIQSerializer extends BinaryPacketIQSerializer {

        StreamingDataIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, StreamingDataIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            final StreamingDataIQ streamingDataIQ = (StreamingDataIQ) object;
            encoder.writeLong(streamingDataIQ.ident);
            encoder.writeLong(streamingDataIQ.offset);
            encoder.writeLong(streamingDataIQ.timestamp);
            encoder.writeLong(streamingDataIQ.streamerPosition);
            encoder.writeInt(streamingDataIQ.streamerLatency);

            if (streamingDataIQ.size <= 0 || streamingDataIQ.data == null) {
                encoder.writeEnum(0);
            } else {
                encoder.writeEnum(1);
                encoder.writeBytes(streamingDataIQ.data, streamingDataIQ.startPos, streamingDataIQ.size);
            }
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            final BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            final long ident = decoder.readLong();
            final long offset = decoder.readLong();
            final long timestamp = decoder.readLong();
            final long streamerPosition = decoder.readLong();
            final int streamerLatency = decoder.readInt();
            final int state = decoder.readEnum();
            final byte[] data;
            final int length;

            if (state == 1) {
                data = decoder.readBytes(null).array();
                length = data.length;
            } else {
                data = null;
                length = 0;
            }

            return new StreamingDataIQ(this, serviceRequestIQ.getRequestId(), ident, offset,
                    streamerPosition, timestamp, streamerLatency, data, 0, length);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new StreamingDataIQSerializer(schemaId, schemaVersion);
    }
}
