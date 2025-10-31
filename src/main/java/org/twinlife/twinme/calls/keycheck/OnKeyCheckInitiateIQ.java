/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.keycheck;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * Start key check session response IQ.
 * <p>
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"773743ea-2d2b-4b64-9ab5-e072571456d8",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"OnKeyCheckInitiateIQ",
 *  "namespace":"org.twinlife.schemas.calls.keycheck",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"errorCode", "type":"enum"}
 *  ]
 * }
 *
 * </pre>
 */
public class OnKeyCheckInitiateIQ extends BinaryPacketIQ {

    static final UUID SCHEMA_ID = UUID.fromString("773743ea-2d2b-4b64-9ab5-e072571456d8");
    static final int SCHEMA_VERSION_1 = 1;
    public static final BinaryPacketIQSerializer IQ_ON_KEY_CHECK_INITIATE_SERIALIZER = new OnStartKeyCheckIQSerializer(SCHEMA_ID, SCHEMA_VERSION_1);

    @NonNull
    public final ErrorCode errorCode;

    public OnKeyCheckInitiateIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull ErrorCode errorCode) {

        super(serializer, requestId);

        this.errorCode = errorCode;
    }

    //
    // Override Object methods
    //
    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        if (BuildConfig.ENABLE_DUMP) {
            super.appendTo(stringBuilder);
            stringBuilder.append(" errorCode=");
            stringBuilder.append(errorCode);
        }
    }

    @Override
    @NonNull
    public String toString() {

        if (BuildConfig.ENABLE_DUMP) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("OnKeyCheckInitiateIQ: ");
            appendTo(stringBuilder);

            return stringBuilder.toString();
        } else {
            return "";
        }
    }

    static class OnStartKeyCheckIQSerializer extends BinaryPacketIQSerializer {

        public OnStartKeyCheckIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, OnKeyCheckInitiateIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder, @NonNull Object object) throws SerializerException {
            super.serialize(serializerFactory, encoder, object);

            OnKeyCheckInitiateIQ keyCheckInitiateIQ = (OnKeyCheckInitiateIQ) object;
            encoder.writeEnum(ErrorCode.fromErrorCode(keyCheckInitiateIQ.errorCode));
        }

        @NonNull
        @Override
        public Object deserialize(@NonNull SerializerFactory serializerFactory, @NonNull Decoder decoder) throws SerializerException {
            BinaryPacketIQ iq = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            ErrorCode errorCode = ErrorCode.toErrorCode(decoder.readEnum());

            return new OnKeyCheckInitiateIQ(this, iq.getRequestId(), errorCode);
        }
    }
}
