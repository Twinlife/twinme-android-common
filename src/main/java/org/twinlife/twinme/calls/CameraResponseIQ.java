/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * Camera response IQ sent as a response of a CameraControlIQ request.
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"c9ba7001-c32d-4545-bdfb-e80ff0db21aa",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"CameraResponseIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"errorCode", "type":"enum"},
 *     {"name":"cameraBitmap", "type":"long"},
 *     {"name":"activeCamera", "type":"int"},
 *     {"name":"minScale", "type":"long"],
 *     {"name":"maxScale", "type":"long"}
 *  ]
 * }
s * </pre>
 */
class CameraResponseIQ extends BinaryPacketIQ {

    private static class CameraResponseIQSerializer extends BinaryPacketIQSerializer {

        CameraResponseIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, CameraResponseIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            CameraResponseIQ cameraResponseIQ = (CameraResponseIQ) object;
            encoder.writeEnum(ErrorCode.fromErrorCode(cameraResponseIQ.errorCode));
            encoder.writeLong(cameraResponseIQ.cameraBitmap);
            encoder.writeInt(cameraResponseIQ.activeCamera);
            encoder.writeLong(cameraResponseIQ.minScale);
            encoder.writeLong(cameraResponseIQ.maxScale);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            ErrorCode errorCode = ErrorCode.toErrorCode(decoder.readEnum());
            long cameraBitmap = decoder.readLong();
            int activeCamera = decoder.readInt();
            long minScale = decoder.readLong();
            long maxScale = decoder.readLong();

            return new CameraResponseIQ(this, serviceRequestIQ.getRequestId(), errorCode, cameraBitmap, activeCamera, minScale, maxScale);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new CameraResponseIQSerializer(schemaId, schemaVersion);
    }

    final ErrorCode errorCode;
    final long cameraBitmap;
    final int activeCamera;
    final long minScale;
    final long maxScale;

    //
    // Override Object methods
    //

    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        super.appendTo(stringBuilder);

        stringBuilder.append(" cameraBitmap=");
        stringBuilder.append(cameraBitmap);
        stringBuilder.append(" activeCamera=");
        stringBuilder.append(activeCamera);
        stringBuilder.append(" minScale=");
        stringBuilder.append(minScale);
        stringBuilder.append(" maxScale=");
        stringBuilder.append(maxScale);
    }

    @NonNull
    public String toString() {

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CameraResponseIQ[");
        appendTo(stringBuilder);
        stringBuilder.append("]");

        return stringBuilder.toString();
    }
    CameraResponseIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull ErrorCode errorCode) {

        super(serializer, requestId);

        this.errorCode = errorCode;
        this.cameraBitmap = 0;
        this.activeCamera = 0;
        this.minScale = 0;
        this.maxScale = 0;
    }

    CameraResponseIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId, @NonNull ErrorCode errorCode,
                     long cameraBitmap, int activeCamera, long minScale, long maxScale) {

        super(serializer, requestId);

        this.errorCode = errorCode;
        this.cameraBitmap = cameraBitmap;
        this.activeCamera = activeCamera;
        this.minScale = minScale;
        this.maxScale = maxScale;
    }
}
