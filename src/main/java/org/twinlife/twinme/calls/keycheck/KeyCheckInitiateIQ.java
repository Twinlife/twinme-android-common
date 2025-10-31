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

import java.util.Locale;
import java.util.UUID;

/**
 * Start key check session request IQ.
 * <p>
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"9c1a7c29-3402-4941-9480-0fd9258f5e5b",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"KeyCheckInitiateIQ",
 *  "namespace":"org.twinlife.schemas.calls.keycheck",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"locale", "type":"string"}
 *  ]
 * }
 *
 * </pre>
 */
public class KeyCheckInitiateIQ extends BinaryPacketIQ {

    static final UUID SCHEMA_ID = UUID.fromString("9c1a7c29-3402-4941-9480-0fd9258f5e5b");
    static final int SCHEMA_VERSION_1 = 1;
    public static final BinaryPacketIQSerializer IQ_KEY_CHECK_INITIATE_SERIALIZER = new StartKeyCheckIQSerializer(SCHEMA_ID, SCHEMA_VERSION_1);

    @NonNull
    public final Locale locale;

    public KeyCheckInitiateIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull Locale locale) {

        super(serializer, requestId);

        this.locale = locale;
    }

    //
    // Override Object methods
    //
    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        if (BuildConfig.ENABLE_DUMP) {
            super.appendTo(stringBuilder);
            stringBuilder.append(" locale=");
            stringBuilder.append(locale);
        }
    }

    @Override
    @NonNull
    public String toString() {

        if (BuildConfig.ENABLE_DUMP) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("KeyCheckInitiateIQ: ");
            appendTo(stringBuilder);

            return stringBuilder.toString();
        } else {
            return "";
        }
    }

    static class StartKeyCheckIQSerializer extends BinaryPacketIQSerializer {

        public StartKeyCheckIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, KeyCheckInitiateIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder, @NonNull Object object) throws SerializerException {
            super.serialize(serializerFactory, encoder, object);

            KeyCheckInitiateIQ keyCheckInitiateIQ = (KeyCheckInitiateIQ) object;
            encoder.writeString(keyCheckInitiateIQ.locale.getLanguage());
        }

        @NonNull
        @Override
        public Object deserialize(@NonNull SerializerFactory serializerFactory, @NonNull Decoder decoder) throws SerializerException {
            BinaryPacketIQ iq = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            String language = decoder.readString();

            Locale locale = new Locale(language);
            if (locale.toLanguageTag().equals("und")) {
                throw new SerializerException("Invalid language: " + language);
            }

            return new KeyCheckInitiateIQ(this, iq.getRequestId(), locale);
        }
    }
}
