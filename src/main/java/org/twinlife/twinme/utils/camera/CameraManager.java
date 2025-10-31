/*
 *  Copyright (c) 2018-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.camera;

import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.Size;

import java.io.File;

/**
 * Camera manager providing a set of abstractions on top of Camera 1 and Camera 2 and suitable for Twinme.
 */
public interface CameraManager {

    enum Mode {
        // Configure to take a photo.
        PHOTO,

        // Configure to scan a QR-code.
        QRCODE
    }

    enum FlashMode {
        // Turn OFF flash
        OFF,

        // Turn ON flash
        ON,

        // Fire only once
        SINGLE
    }

    enum ErrorCode {

        // There is no camera.
        NO_CAMERA,

        // The permission to access the camera is denied.
        NO_PERMISSION,

        // The camera is used by another application.
        CAMERA_IN_USE,

        // General error when opening the camera.
        CAMERA_ERROR
    }

    enum State {
        // Camera manager is not opened: an open() is necessary or is in progress.
        UNINITIALIZED,

        // An open() is in progress.
        STARTING,

        // Camera manager is ready and configured.  The onCameraReady() callback is called
        // the first time we enter in this state (after open()).
        READY,

        // Camera manager is taking a picture (takePicture() was called).
        // The onPicture() callback will be called with the picture and the state will change to READY.
        WAITING_PICTURE,

        // Error while opening the camera.  The onCameraError() is called when we enter in this state.
        ERROR,

        // Camera manager is shutting down.
        STOPPING
    }

    interface CameraCallback {

        void onCameraReady();

        boolean onPicture(@NonNull byte[] data, int width, int height);

        void onRecordVideoStart();

        void onRecordVideoStop(@Nullable File videoFile);

        void onCameraError(@NonNull ErrorCode errorCode);
    }

    void open(@NonNull SurfaceTexture surfaceTexture, boolean backCamera);

    boolean isOpened();

    Size getCameraResolution();

    boolean isCameraFacingFront();

    int getDisplayOrientation();

    void close();

    void takePicture();

    void startRecordVideo();

    void stopRecordVideo();

    void setTorch(@NonNull FlashMode mode);

    void setZoom(int zoom);

    int getMaxZoom();
}
