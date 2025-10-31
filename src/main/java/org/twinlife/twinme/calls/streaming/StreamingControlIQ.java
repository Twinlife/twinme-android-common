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
 * A streaming control operation.
 *
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"a080a7a6-59fe-4463-8ac4-61d897a2aa50",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"StreamingControlIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"ident", "type":"long"},
 *     {"name":"control", "type":"enum"},
 *     {"name":"length", "type":"long"},
 *     {"name":"timestamp", "type":"long"},
 *     {"name":"position", "type":"long"},
 *     {"name":"latency", "type":"int"}
 *  ]
 * }
 *
 * </pre>
 *
 * - Streaming starts either with a START_AUDIO_STREAMING or a START_VIDEO_STREAMING.
 * - The streaming can be paused with PAUSE_STREAMING and then resumed with RESUME_STREAMING,
 * - We can seek at a given position with SEEK_STREAMING,
 * - Streaming is stopped with STOP_STREAMING
 * - Peer can ask some operation on the streamer with the ASK_PAUSE_STREAMING, ASK_RESUME_STREAMING,
 *   ASK_SEEK_STREAMING and ASK_STOP_STREAMING
 * - Peer can give back their status with STREAMING_STATUS
 */
public class StreamingControlIQ extends BinaryPacketIQ {
    private static final UUID STREAMING_CONTROL_SCHEMA_ID = UUID.fromString("a080a7a6-59fe-4463-8ac4-61d897a2aa50");
    public static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_STREAMING_CONTROL_SERIALIZER = StreamingControlIQ.createSerializer(STREAMING_CONTROL_SCHEMA_ID, 1);

    public enum Mode {
        UNKNOWN,

        // Start, stop and other operations for the streaming (values 1..10).
        START_AUDIO_STREAMING,
        START_VIDEO_STREAMING,
        PAUSE_STREAMING,
        RESUME_STREAMING,
        SEEK_STREAMING,
        STOP_STREAMING,

        // Queries from the peer to operate on the streamer (values 11..20).
        ASK_PAUSE_STREAMING,
        ASK_RESUME_STREAMING,
        ASK_SEEK_STREAMING,
        ASK_STOP_STREAMING,

        // Streaming status feedback values (20..30).
        STREAMING_STATUS_PLAYING,
        STREAMING_STATUS_PAUSED,
        STREAMING_STATUS_READY,
        STREAMING_STATUS_UNSUPPORTED,
        STREAMING_STATUS_ERROR,
        STREAMING_STATUS_STOPPED,
        STREAMING_STATUS_COMPLETED
    }

    public final long ident;
    @NonNull
    public final Mode control;
    public final long length;
    public final long timestamp;
    public final long position;
    public final int latency;

    public StreamingControlIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId,
                              long ident, @NonNull Mode control, long length, long timestamp,
                              long position, int latency) {

        super(serializer, requestId);

        this.ident = ident;
        this.control = control;
        this.length = length;
        this.timestamp = timestamp;
        this.position = position;
        this.latency = latency;
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
            stringBuilder.append(" control=");
            stringBuilder.append(control);
            stringBuilder.append(" length=");
            stringBuilder.append(length);
            stringBuilder.append(" timestamp=");
            stringBuilder.append(timestamp);
            stringBuilder.append(" position=");
            stringBuilder.append(position);
            stringBuilder.append(" latency=");
            stringBuilder.append(latency);
        }
    }

    @NonNull
    public String toString() {

        final StringBuilder stringBuilder = new StringBuilder();
        if (BuildConfig.DEBUG) {
            stringBuilder.append("StreamingControlIQ[");
            appendTo(stringBuilder);
            stringBuilder.append("]");
        }
        return stringBuilder.toString();
    }

    private static class StreamingControlIQSerializer extends BinaryPacketIQSerializer {

        StreamingControlIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, StreamingControlIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            final StreamingControlIQ streamingControlIQ = (StreamingControlIQ) object;
            encoder.writeLong(streamingControlIQ.ident);
            switch (streamingControlIQ.control) {
                case START_AUDIO_STREAMING:
                    encoder.writeEnum(1);
                    break;

                case START_VIDEO_STREAMING:
                    encoder.writeEnum(2);
                    break;

                case PAUSE_STREAMING:
                    encoder.writeEnum(3);
                    break;

                case RESUME_STREAMING:
                    encoder.writeEnum(4);
                    break;

                case SEEK_STREAMING:
                    encoder.writeEnum(5);
                    break;

                case STOP_STREAMING:
                    encoder.writeEnum(6);
                    break;

                    // Queries operation range 11..20
                case ASK_PAUSE_STREAMING:
                    encoder.writeEnum(11);
                    break;

                case ASK_RESUME_STREAMING:
                    encoder.writeEnum(12);
                    break;

                case ASK_SEEK_STREAMING:
                    encoder.writeEnum(13);
                    break;

                case ASK_STOP_STREAMING:
                    encoder.writeEnum(14);
                    break;

                    // Status operations range 21..30
                case STREAMING_STATUS_PLAYING:
                    encoder.writeEnum(21);
                    break;

                case STREAMING_STATUS_PAUSED:
                    encoder.writeEnum(22);
                    break;

                case STREAMING_STATUS_READY:
                    encoder.writeEnum(23);
                    break;

                case STREAMING_STATUS_UNSUPPORTED:
                    encoder.writeEnum(24);
                    break;

                case STREAMING_STATUS_ERROR:
                    encoder.writeEnum(25);
                    break;

                case STREAMING_STATUS_STOPPED:
                    encoder.writeEnum(26);
                    break;

                case STREAMING_STATUS_COMPLETED:
                    encoder.writeEnum(27);
                    break;

                default:
                    // When we serialize, we must know how to send the control command.
                    // UNKNOWN is not used when sending but can be obtained when we deserialize.
                    throw new SerializerException();
            }
            encoder.writeLong(streamingControlIQ.length);
            encoder.writeLong(streamingControlIQ.timestamp);
            encoder.writeLong(streamingControlIQ.position);
            encoder.writeInt(streamingControlIQ.latency);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            final BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            final long ident = decoder.readLong();
            final Mode control;
            switch (decoder.readEnum()) {
                case 1:
                    control = Mode.START_AUDIO_STREAMING;
                    break;

                case 2:
                    control = Mode.START_VIDEO_STREAMING;
                    break;

                case 3:
                    control = Mode.PAUSE_STREAMING;
                    break;

                case 4:
                    control = Mode.RESUME_STREAMING;
                    break;

                case 5:
                    control = Mode.SEEK_STREAMING;
                    break;

                case 6:
                    control = Mode.STOP_STREAMING;
                    break;

                case 11:
                    control = Mode.ASK_PAUSE_STREAMING;
                    break;

                case 12:
                    control = Mode.ASK_RESUME_STREAMING;
                    break;

                case 13:
                    control = Mode.ASK_SEEK_STREAMING;
                    break;

                case 14:
                    control = Mode.ASK_STOP_STREAMING;
                    break;

                case 21:
                    control = Mode.STREAMING_STATUS_PLAYING;
                    break;

                case 22:
                    control = Mode.STREAMING_STATUS_PAUSED;
                    break;

                case 23:
                    control = Mode.STREAMING_STATUS_READY;
                    break;

                case 24:
                    control = Mode.STREAMING_STATUS_UNSUPPORTED;
                    break;

                case 25:
                    control = Mode.STREAMING_STATUS_ERROR;
                    break;

                case 26:
                    control = Mode.STREAMING_STATUS_STOPPED;
                    break;

                case 27:
                    control = Mode.STREAMING_STATUS_COMPLETED;
                    break;

                default:
                    control = Mode.UNKNOWN;
                    break;
            }
            long length = decoder.readLong();
            long timestamp = decoder.readLong();
            long position = decoder.readLong();
            int latency = decoder.readInt();

            return new StreamingControlIQ(this, serviceRequestIQ.getRequestId(), ident, control, length,
                    timestamp, position, latency);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new StreamingControlIQSerializer(schemaId, schemaVersion);
    }
}
