/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.keycheck;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * Terminate Key Check IQ.
 * <p>
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"f57606a3-9455-4efe-b375-38e1a142465f",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"TerminateKeyCheckIQ",
 *  "namespace":"org.twinlife.schemas.calls.keycheck",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"result", "type":"boolean"}
 *  ]
 * }
 *
 * </pre>
 */
public class TerminateKeyCheckIQ extends BinaryPacketIQ {

    static final UUID SCHEMA_ID = UUID.fromString("f57606a3-9455-4efe-b375-38e1a142465f");
    static final int SCHEMA_VERSION_1 = 1;
    public static final BinaryPacketIQSerializer IQ_TERMINATE_KEY_CHECK_SERIALIZER = new TerminateKeyCheckIQ.WordCheckIQSerializer(SCHEMA_ID, SCHEMA_VERSION_1);

    public final boolean result;

    public TerminateKeyCheckIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, boolean result) {

        super(serializer, requestId);

        this.result = result;
    }

    //
    // Override Object methods
    //
    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        if (BuildConfig.ENABLE_DUMP) {
            super.appendTo(stringBuilder);
            stringBuilder.append(" result=");
            stringBuilder.append(result);
        }
    }

    @Override
    @NonNull
    public String toString() {

        if (BuildConfig.ENABLE_DUMP) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("TerminateKeyCheckIQ: ");
            appendTo(stringBuilder);

            return stringBuilder.toString();
        } else {
            return "";
        }
    }

    static class WordCheckIQSerializer extends BinaryPacketIQSerializer {

        public WordCheckIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, TerminateKeyCheckIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder, @NonNull Object object) throws SerializerException {
            super.serialize(serializerFactory, encoder, object);

            TerminateKeyCheckIQ terminateKeyCheckIQ = (TerminateKeyCheckIQ) object;

            encoder.writeBoolean(terminateKeyCheckIQ.result);
        }

        @NonNull
        @Override
        public Object deserialize(@NonNull SerializerFactory serializerFactory, @NonNull Decoder decoder) throws SerializerException {
            BinaryPacketIQ iq = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            boolean result = decoder.readBoolean();

            return new TerminateKeyCheckIQ(this, iq.getRequestId(), result);
        }
    }
}
