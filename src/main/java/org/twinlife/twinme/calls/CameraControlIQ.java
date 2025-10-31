/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls;

import androidx.annotation.NonNull;
import org.twinlife.twinlife.Decoder;
import org.twinlife.twinlife.Encoder;
import org.twinlife.twinlife.SerializerException;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.util.BinaryPacketIQ;

import java.util.UUID;

/**
 * Camera control IQ sent to change the configuration of the camera on the peer device.
 * Schema version 1
 * <pre>
 * {
 *  "schemaId":"6512ff06-7c18-4de4-8760-61b87b9169a5",
 *  "schemaVersion":"1",
 *
 *  "type":"record",
 *  "name":"CameraControlIQ",
 *  "namespace":"org.twinlife.schemas.calls",
 *  "super":"org.twinlife.schemas.BinaryPacketIQ"
 *  "fields": [
 *     {"name":"control": "type":"int"},
 *     {"name":"camera", "type":"int"},
 *     {"name":"scale", "type":"int"},
 *  ]
 * }
 *
 * </pre>
 */
class CameraControlIQ extends BinaryPacketIQ {

    enum Mode {
        CHECK,
        ON,
        OFF,
        SELECT,
        ZOOM,
        STOP
    }

    private static class CameraControlIQSerializer extends BinaryPacketIQSerializer {

        CameraControlIQSerializer(UUID schemaId, int schemaVersion) {

            super(schemaId, schemaVersion, CameraControlIQ.class);
        }

        @Override
        public void serialize(@NonNull SerializerFactory serializerFactory, @NonNull Encoder encoder,
                              @NonNull Object object) throws SerializerException {

            super.serialize(serializerFactory, encoder, object);

            final CameraControlIQ cameraControlIQ = (CameraControlIQ) object;
            switch (cameraControlIQ.control) {
                case CHECK:
                    encoder.writeEnum(0);
                    break;
                case ON:
                    encoder.writeEnum(1);
                    break;
                case OFF:
                    encoder.writeEnum(2);
                    break;
                case SELECT:
                    encoder.writeEnum(3);
                    break;
                case ZOOM:
                    encoder.writeEnum(4);
                    break;
                case STOP:
                    encoder.writeEnum(5);
                    break;
            }
            encoder.writeInt(cameraControlIQ.camera);
            encoder.writeInt(cameraControlIQ.scale);
        }

        @Override
        @NonNull
        public Object deserialize(@NonNull SerializerFactory serializerFactory,
                                  @NonNull Decoder decoder) throws SerializerException {

            BinaryPacketIQ serviceRequestIQ = (BinaryPacketIQ) super.deserialize(serializerFactory, decoder);

            Mode control;
            switch (decoder.readEnum()) {
                case 0:
                    control = Mode.CHECK;
                    break;
                case 1:
                    control = Mode.ON;
                    break;
                case 2:
                    control = Mode.OFF;
                    break;
                case 3:
                    control = Mode.SELECT;
                    break;
                case 4:
                    control = Mode.ZOOM;
                    break;
                case 5:
                    control = Mode.STOP;
                    break;
                default:
                    throw new SerializerException("");
            }
            int camera = decoder.readInt();
            int scale = decoder.readInt();

            return new CameraControlIQ(this, serviceRequestIQ.getRequestId(), control, camera, scale);
        }
    }

    @NonNull
    public static BinaryPacketIQSerializer createSerializer(@NonNull UUID schemaId, int schemaVersion) {

        return new CameraControlIQSerializer(schemaId, schemaVersion);
    }

    @NonNull
    final Mode control;
    final int camera;
    final int scale;

    //
    // Override Object methods
    //

    @Override
    protected void appendTo(@NonNull StringBuilder stringBuilder) {

        super.appendTo(stringBuilder);

        stringBuilder.append(" control=");
        stringBuilder.append(control);
        stringBuilder.append(" camera=");
        stringBuilder.append(camera);
        stringBuilder.append(" scale=");
        stringBuilder.append(scale);
    }

    @NonNull
    public String toString() {

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CameraControlIQ[");
        appendTo(stringBuilder);
        stringBuilder.append("]");

        return stringBuilder.toString();
    }

    CameraControlIQ(@NonNull BinaryPacketIQSerializer serializer, long requestId,
                    @NonNull Mode control, int camera, int scale) {

        super(serializer, requestId);

        this.control = control;
        this.camera = camera;
        this.scale = scale;
    }
}
