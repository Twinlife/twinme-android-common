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
 * Result of a word check IQ.
 * <p>
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"949a64db-deb4-4266-9a2a-b680c80ecc07",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"WordCheckIQ",
 *  "namespace":"org.twinlife.schemas.calls.keycheck",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"wordIndex", "type":"int"},
 *     {"name":"errorCode", "type":"enum"}
 *  ]
 * }
 *
 * </pre>
 */
public class WordCheckIQ extends BinaryPacketIQ {

    static final UUID SCHEMA_ID = UUID.fromString("949a64db-deb4-4266-9a2a-b680c80ecc07");
    static final int SCHEMA_VERSION_1 = 1;
    public static final BinaryPacketIQSerializer IQ_WORD_CHECK_SERIALIZER = new WordCheckIQ.WordCheckIQSerializer(SCHEMA_ID, SCHEMA_VERSION_1);

    @NonNull
    public final WordCheckResult result;

    public WordCheckIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull WordCheckResult result) {

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
            stringBuilder.append("WordCheckIQ: ");
            appendTo(stringBuilder);

            return stringBuilder.toString();
        } else {
            return "";
        }
    }

    static class WordCheckIQSerializer extends BinaryPacketIQSerializer {

        public WordCheckIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, WordCheckIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder, @NonNull Object object) throws SerializerException {
            super.serialize(serializerFactory, encoder, object);

            WordCheckIQ wordCheckIQ = (WordCheckIQ) object;

            encoder.writeInt(wordCheckIQ.result.wordIndex);
            encoder.writeBoolean(wordCheckIQ.result.ok);
        }

        @NonNull
        @Override
        public Object deserialize(@NonNull SerializerFactory serializerFactory, @NonNull Decoder decoder) throws SerializerException {
            BinaryPacketIQ iq = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            int wordIndex = decoder.readInt();
            boolean ok = decoder.readBoolean();

            return new WordCheckIQ(this, iq.getRequestId(), new WordCheckResult(wordIndex, ok));
        }
    }
}