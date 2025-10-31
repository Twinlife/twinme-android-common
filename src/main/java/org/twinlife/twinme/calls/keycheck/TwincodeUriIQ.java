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
 *  "schemaId":"413c9c59-2b93-4010-8f6c-bd4f64ce5d9d",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"TwincodeUriIQ",
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
public class TwincodeUriIQ extends BinaryPacketIQ {

    static final UUID SCHEMA_ID = UUID.fromString("413c9c59-2b93-4010-8f6c-bd4f64ce5d9d");
    static final int SCHEMA_VERSION_1 = 1;
    public static final BinaryPacketIQSerializer IQ_TWINCODE_URI_SERIALIZER = new TwincodeUriIQSerializer(SCHEMA_ID, SCHEMA_VERSION_1);

    @NonNull
    public final String uri;

    public TwincodeUriIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull String uri) {

        super(serializer, requestId);

        this.uri = uri;
    }

    //
    // Override Object methods
    //
    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        if (BuildConfig.ENABLE_DUMP) {
            super.appendTo(stringBuilder);
            stringBuilder.append(" uri=");
            stringBuilder.append(uri);
        }
    }

    @Override
    @NonNull
    public String toString() {

        if (BuildConfig.ENABLE_DUMP) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("TwincodeUriIQ: ");
            appendTo(stringBuilder);

            return stringBuilder.toString();
        } else {
            return "";
        }
    }

    static class TwincodeUriIQSerializer extends BinaryPacketIQSerializer {

        public TwincodeUriIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, TwincodeUriIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder, @NonNull Object object) throws SerializerException {
            super.serialize(serializerFactory, encoder, object);

            TwincodeUriIQ twincodeUriIQ = (TwincodeUriIQ) object;

            encoder.writeString(twincodeUriIQ.uri);
        }

        @NonNull
        @Override
        public Object deserialize(@NonNull SerializerFactory serializerFactory, @NonNull Decoder decoder) throws SerializerException {
            BinaryPacketIQ iq = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            String uri = decoder.readString();

            return new TwincodeUriIQ(this, iq.getRequestId(), uri);
        }
    }
}