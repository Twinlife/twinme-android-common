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
 * Streaming information.
 *
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"30991309-e91f-4295-8a9c-995fcfaf042e",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"StreamingInfoIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"ident", "type":"long"},
 *     {"name":"title", "type":"string"},
 *     {"name":"album", [null, "type":"string"]},
 *     {"name":"artist", [null, "type":"string"]},
 *     {"name":"artwork", [null, "type":"bytes"]}
 *     {"name":"duration", "type":"long"}
 *  ]
 * }
 *
 * </pre>
 */
public class StreamingInfoIQ extends BinaryPacketIQ {
    private static final UUID STREAMING_INFO_SCHEMA_ID = UUID.fromString("30991309-e91f-4295-8a9c-995fcfaf042e");
    public static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_STREAMING_INFO_SERIALIZER = createSerializer(STREAMING_INFO_SCHEMA_ID, 1);

    public final long ident;
    @NonNull
    public final String title;
    @Nullable
    public final String album;
    @Nullable
    public final String artist;
    @Nullable
    public final byte[] artwork;
    public final long duration;

    public StreamingInfoIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId,
                           long ident, @NonNull String title, @Nullable String album, @Nullable String artist,
                           @Nullable byte[] artwork, long duration) {

        super(serializer, requestId);

        this.ident = ident;
        this.title = title;
        this.album = album;
        this.artist = artist;
        this.artwork = artwork;
        this.duration = duration;
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
            stringBuilder.append(" title=");
            stringBuilder.append(title);
            stringBuilder.append(" album=");
            stringBuilder.append(album);
            stringBuilder.append(" artist=");
            stringBuilder.append(artist);
            stringBuilder.append(" duration=");
            stringBuilder.append(duration);
        }
    }

    @NonNull
    public String toString() {

        final StringBuilder stringBuilder = new StringBuilder();
        if (BuildConfig.DEBUG) {
            stringBuilder.append("StreamingInfoIQ[");
            appendTo(stringBuilder);
            stringBuilder.append("]");
        }
        return stringBuilder.toString();
    }

    protected int getBufferSize() {

        if (artwork != null) {
            return artwork.length + SERIALIZER_BUFFER_DEFAULT_SIZE;
        } else {
            return SERIALIZER_BUFFER_DEFAULT_SIZE;
        }
    }

    private static class StreamingInfoIQSerializer extends BinaryPacketIQSerializer {

        StreamingInfoIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, StreamingInfoIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            final StreamingInfoIQ streamingInfoIQ = (StreamingInfoIQ) object;
            encoder.writeLong(streamingInfoIQ.ident);
            encoder.writeString(streamingInfoIQ.title);
            encoder.writeOptionalString(streamingInfoIQ.album);
            encoder.writeOptionalString(streamingInfoIQ.artist);
            encoder.writeOptionalBytes(streamingInfoIQ.artwork);
            encoder.writeLong(streamingInfoIQ.duration);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            final BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            final long ident = decoder.readLong();
            final String title = decoder.readString();
            final String album = decoder.readOptionalString();
            final String artist = decoder.readOptionalString();
            final byte[] artwork = decoder.readOptionalBytes(null);
            final long duration = decoder.readLong();

            return new StreamingInfoIQ(this, serviceRequestIQ.getRequestId(), ident, title, album, artist, artwork, duration);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new StreamingInfoIQSerializer(schemaId, schemaVersion);
    }
}
