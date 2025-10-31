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

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinlife.util.Utils;
import org.webrtc.Size;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Camera manager using the Android camera 1 API.
 * @noinspection deprecation
 */
public class Camera1ManagerImpl extends AbstractCameraManager implements Camera.AutoFocusCallback, Camera.PreviewCallback {
    private static final String LOG_TAG = "Camera1ManagerImpl";
    private static final boolean DEBUG = false;

    private static final int AUTOFOCUS_DELAY_MS = 2000;

    private int mCameraId = -1;
    private boolean mAutoFocus;
    private boolean mAutoFocusPending = false;
    private boolean mPreviewing;
    @Nullable
    private Camera mCamera;
    private MediaRecorder mMediaRecorder;
    @Nullable
    private File mVideoFile;
    private final Runnable mAutoFocusHandler = this::updateAutoFocusState;

    // Auto focus start/stop/restart is handled by several steps represented by AutoFocusState
    // with a delay between each of them.
    enum AutoFocusState {
        IDLE,
        STARTING,
        WAITING,
        SUCCESS,
        FAILED
    }
    private AutoFocusState mFocusState = AutoFocusState.IDLE;

    // To improve QR-code detection for some camera, we setup a list of exposures between the min and max
    // supported by the camera and select a new exposure after several frames.  We start at the middle of
    // range (0) and increase the range until it is covered.  That is we try:
    // [0]
    // [-1 0 1]
    // [-2 -1 0 1 2]
    // [-3 -2 -1 0 1 2 3]
    // ...
    // Until we reach the mExposureMin/mExposureMax defined by the camera.
    private int mExposureMin;
    private int mExposureMax;
    private int mExposureCounter;
    private int mExposureIncrement;
    private int mExposureValue;

    // The mExposureValue varies between mExposureCurrentMin and mExposureCurrentMax.
    // That range is dynamic and either grows or shrink to cover the full camera range.
    // See nextExposure().
    private boolean mExposureGrow;
    private int mExposureCurrentMin;
    private int mExposureCurrentMax;

    // Number of frames to check before changing the exposure (used to initialise mExposureCounter).
    private static final int NB_FRAMES_PER_EXPOSURE_CHECK = 5;

    public Camera1ManagerImpl(@NonNull Activity activity, @NonNull TextureView textureView,
                              @NonNull CameraCallback cameraCallback, @NonNull Mode mode) {
        super(activity, textureView, cameraCallback, mode);
        if (DEBUG) {
            Log.d(LOG_TAG, "Camera1ManagerImpl");
        }
    }

    @Override
    protected void openInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "openInternal");
        }

        checkIsOnCameraThread();

        // Release the camera if it is opened.
        if (mCamera != null) {
            stopPreview();
            if (mCamera != null) {
                mCamera.release();
            }
            mCamera = null;
        }

        mCameraId = -1;

        // If we have a very very slow camera opening, it is possible that the close() was called before we proceed.
        // In that case, don't try to open the camera, the closeInternal() is queued and will do the necessary clearnup.
        if (mState == State.STOPPING) {
            return;
        }

        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();

        // Select the camera (front or back).
        final int facing = mAskedCameraFacing ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT;
        final int numberOfCameras = Camera.getNumberOfCameras();
        int defaultId = -1;
        for (int i = 0; i < numberOfCameras; i++) {
            try {
                Camera.getCameraInfo(i, cameraInfo);
                if (facing == cameraInfo.facing) {
                    mCameraId = i;

                } else if (defaultId < 0) {
                    defaultId = i;
                }
            } catch (RuntimeException exception) {
                Log.d(LOG_TAG, "Camera error: " + exception);
            }
        }

        if (mCameraId < 0) {
            mCameraId = defaultId;
        }

        if (mCameraId < 0) {
            mState = State.ERROR;
            mCameraCallback.onCameraError(ErrorCode.NO_CAMERA);
            return;
        }

        try {
            Camera.getCameraInfo(mCameraId, cameraInfo);
            mCameraFacing = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;

        } catch (RuntimeException exception) {
            mState = State.ERROR;
            mCameraCallback.onCameraError(ErrorCode.NO_CAMERA);
            return;
        }

        try {
            mCamera = Camera.open(mCameraId);

        } catch (RuntimeException exception) {
            mCameraId = -1;
            mState = State.ERROR;
            mCameraCallback.onCameraError(ErrorCode.CAMERA_IN_USE);
            return;
        }

        // To simulate a slow Camera.open(), uncomment the following:
        //
        // try {
        //    Thread.sleep(5000);
        // } catch (Exception exception) {
        //
        // }

        Camera.Parameters parameters;
        try {
            parameters = mCamera.getParameters();

            List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
            Size[] sizes = new Size[supportedPreviewSizes.size()];
            for (int i = 0; i < sizes.length; i++) {
                Camera.Size size = supportedPreviewSizes.get(i);
                sizes[i] = new Size(size.width, size.height);
            }

            Camera.Size previewSize = parameters.getPreviewSize();
            mMaxZoom = parameters.getMaxZoom();
            mCameraResolution = findPreviewSize(sizes, mTextureView.getWidth(), mTextureView.getHeight(), new Size(previewSize.width, previewSize.height));
            if (mMode == Mode.QRCODE) {
                mCameraPictureResolution = mCameraResolution;
            } else {
                mCameraPictureResolution = findPictureSize(parameters);
            }
        } catch (RuntimeException exception) {
            if (mCamera != null) {
                mCamera.release();
            }
            mCamera = null;
            mCameraId = -1;
            mMaxZoom = 1;
            mState = State.ERROR;
            mCameraCallback.onCameraError(ErrorCode.CAMERA_IN_USE);
            return;
        }

        if (DEBUG) {
            Log.e(LOG_TAG, "Using camera " + mCameraResolution.width + "x" + mCameraResolution.height
                    + " and picture " + mCameraPictureResolution.width + "x" + mCameraPictureResolution.height
                    + " with texture "
                    + mTextureView.getWidth() + "x" + mTextureView.getHeight());
        }

        // We need orientation change only for the photo mode.
        if (mMode == Mode.PHOTO) {
            mOrientationListener.enable();
        }

        setCameraParameters(parameters);

        int displayOrientation = 0;
        switch (mActivity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0:
                displayOrientation = 0;
                break;
            case Surface.ROTATION_90:
                displayOrientation = 90;
                break;
            case Surface.ROTATION_180:
                displayOrientation = 180;
                break;
            case Surface.ROTATION_270:
                displayOrientation = 270;
                break;
        }

        int rotation;

        if (mCameraFacing) {
            rotation = (cameraInfo.orientation + displayOrientation) % 360;
            // compensate the mirror
            mCameraOrientation = (360 - rotation) % 360;
        } else { // back-facing
            rotation = (cameraInfo.orientation - displayOrientation + 360) % 360;
            mCameraOrientation = rotation;
        }
        if (DEBUG) {
            Log.e(LOG_TAG, "Set camera display orientation " + mCameraOrientation + " with rotation " + rotation);
        }

        try {
            mCamera.setDisplayOrientation(mCameraOrientation);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Camera display orientation error: " + exception);
        }

        // Get the configured camera configuration again to get the preview size.
        try {
            parameters = mCamera.getParameters();
            Camera.Size size = parameters.getPreviewSize();
            mCameraResolution = new Size(size.width, size.height);

        } catch (Exception exception) {
            Log.e(LOG_TAG, "Camera parameters error: " + exception);
        }

        try {
            parameters.setRotation(rotation);
            mCamera.setParameters(parameters);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Camera rotation error: " + exception);
        }

        setSizes();

        try {
            mCamera.setPreviewTexture(mSurfaceTexture);

        } catch (RuntimeException runtimeException) {
            Log.d(LOG_TAG, "Camera error: " + runtimeException);

        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception: " + exception);
        }

        // Change to READY state and call the onCameraReady() callback to let the caller setup its preview.
        // The onCameraReady() is called from the Camera Thread and it must do all the necessary setup
        // so that the view is ready to accept a frame.  It must not do this setup from the main UI thread
        // because it may receive a frame while it is not fully initialized!
        synchronized (this) {
            // Be careful: it is possible that close() is called, in that case we should not change to the READY state.
            if (mState == State.STOPPING) {
                return;
            }

            mState = State.READY;
        }
        mCameraCallback.onCameraReady();

        try {
            mCamera.startPreview();
            mPreviewing = true;

        } catch (RuntimeException ex) {
            // A RuntimeException can be raised if the camera auto focus fails.
            Log.d(LOG_TAG, "Camera error: " + ex);
        }

        setOneShotPreviewCallback();
        updateAutoFocusState();
    }

    @Override
    protected void closeInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "closeInternal");
        }

        checkIsOnCameraThread();

        mOrientationListener.disable();

        // Remove the auto focus message if it was posted.
        if (mAutoFocusPending) {
            mCameraHandler.removeCallbacks(mAutoFocusHandler);
            mAutoFocusPending = false;
        }

        stopPreview();

        // Make sure to release the media recorder in case stopRecordVideoInternal was not called.
        if (mMediaRecorder != null) {
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        // The video file was not used by the CameraActivity: cleanup.
        if (mVideoFile != null) {
           Utils.deleteFile(LOG_TAG, mVideoFile);
           mVideoFile = null;
        }

        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
        mCameraId = -1;

        mSurfaceTexture = null;

        // Release the camera thread: it will stop when it is no longer in use.
        mCameraThread.release();
    }

    @Override
    protected void takePictureInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "takePicture");
        }

        checkIsOnCameraThread();

        // Check and switch to the WAITING_PICTURE state.
        synchronized (this) {
            if (mState != State.READY || mCamera == null) {

                return;
            }

            mState = State.WAITING_PICTURE;
        }

        try {
            mCamera.takePicture(null, null, (data, camera) -> {

                // Restore the READY state unless the camera managed is closed.
                boolean waitingPicture;
                synchronized (this) {
                    waitingPicture = mState == State.WAITING_PICTURE;
                    if (waitingPicture) {
                        mState = State.READY;
                    }
                }
                if (waitingPicture) {
                    mCameraCallback.onPicture(data, mCameraResolution.width, mCameraResolution.height);

                    // Be careful: the camera can be stopping when we are back from onPicture().
                    if (isReady() && mPreviewing) {
                        try {
                            mCamera.startPreview();
                        } catch (RuntimeException exception) {
                            Log.d(LOG_TAG, "Camera error: " + exception);
                        }
                    }
                }
            });
        } catch (Exception exception) {
            Log.e(LOG_TAG, "takePicture failed: ", exception);
        }
    }

    @Override
    protected void startRecordVideoInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startRecordVideoInternal");
        }

        checkIsOnCameraThread();

        if (prepareVideoRecorder()) {
            try {
                mMediaRecorder.start();
                mCameraCallback.onRecordVideoStart();
            } catch (RuntimeException exception) {
                Log.d(LOG_TAG, "Media recorder exception", exception);
            }
        } else {
            releaseMediaRecorder();
        }
    }

    @Override
    protected void stopRecordVideoInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopRecordVideoInternal");
        }

        checkIsOnCameraThread();

        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (RuntimeException exception) {
                Log.d(LOG_TAG, "Media recorder exception", exception);
            }
        }
        releaseMediaRecorder();

        mCameraCallback.onRecordVideoStop(mVideoFile);
        mVideoFile = null;
        if (mCamera != null) {
            mCamera.lock();
        }
    }

    @Override
    protected void setTorchInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "setTorchInternal currentFlashMode=" + mCurrentFlashMode);
        }

        checkIsOnCameraThread();

        if (!isReady() || mCamera == null) {

            return;
        }

        try {
            Camera.Parameters parameters = mCamera.getParameters();

            String flashMode = parameters.getFlashMode();
            FlashMode newMode = mCurrentFlashMode;
            boolean isOk = false;
            switch (newMode) {
                case ON:
                    isOk = Camera.Parameters.FLASH_MODE_ON.equals(flashMode) || Camera.Parameters.FLASH_MODE_TORCH.equals(flashMode);
                    break;

                case OFF:
                    isOk = Camera.Parameters.FLASH_MODE_OFF.equals(flashMode);
                    break;

                case SINGLE:
                    isOk = Camera.Parameters.FLASH_MODE_RED_EYE.equals(flashMode);
                    break;
            }
            if (isOk) {
                return;
            }

            doSetTorch(parameters, newMode);

        } catch (RuntimeException exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }
    }

    @Override
    protected void setZoomInternal() {
        if (DEBUG) {
            Log.d(LOG_TAG, "setZoomInternal zoom=" + mCurrentZoom);
        }

        checkIsOnCameraThread();

        if (!isReady() || mCamera == null) {

            return;
        }

        try {
            Camera.Parameters parameters = mCamera.getParameters();
            doSetZoom(parameters, mCurrentZoom);
        } catch (RuntimeException exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }
    }

    //
    // Override Camera.AutoFocusCallback methods
    //

    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAutoFocus success=" + success + " camera=" + camera);
        }

        checkIsOnCameraThread();

        if (!isOpened() || mCamera == null || mCamera != camera || mMode != Mode.QRCODE) {

            return;
        }

        setOneShotPreviewCallback();

        if (mFocusState == AutoFocusState.WAITING && success) {
            mFocusState = AutoFocusState.SUCCESS;
        } else if (!success) {
            mFocusState = AutoFocusState.FAILED;
        }
        if (!success) {
            mExposureValue = 0;
            mExposureCurrentMin = 0;
            mExposureCurrentMax = 0;
            mExposureGrow = true;
            mExposureIncrement = 1;
            mExposureCounter = NB_FRAMES_PER_EXPOSURE_CHECK * 3;
        }
        updateAutoFocusState();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPreviewFrame camera=" + camera);
        }

        checkIsOnCameraThread();

        if (!isOpened()) {

            return;
        }

        if (mCameraCallback.onPicture(data, mCameraResolution.width, mCameraResolution.height)) {
            setOneShotPreviewCallback();
            nextExposure();
        }
    }

    @Override
    protected synchronized void stopPreview() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopPreview");
        }

        synchronized (this) {
            if (!isOpened() || mCamera == null || !mPreviewing) {

                return;
            }

            mPreviewing = false;
        }

        try {
            mCamera.stopPreview();
        } catch (Exception exception) {
            Log.d(LOG_TAG, "stopPreview failed", exception);
        }

        if (mAutoFocus) {
            try {
                mCamera.cancelAutoFocus();
            } catch (Exception exception) {
                Log.e(LOG_TAG, "stopPreview - cancelAutoFocus failed", exception);
            }
        }
    }

    private void setOneShotPreviewCallback() {
        if (DEBUG) {
            Log.d(LOG_TAG, "setOneShotPreviewCallback");
        }

        checkIsOnCameraThread();

        if (!isReady() || mCamera == null) {

            return;
        }

        try {
            if (mMode == Mode.QRCODE) {
                mCamera.setOneShotPreviewCallback(this);
            }
        } catch (RuntimeException exception) {
            // A RuntimeException can be raised if the camera auto focus fails.
            Log.d(LOG_TAG, "Camera error: " + exception);
        }
    }

    private void updateAutoFocusState() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateAutoFocusState state=" + mFocusState);
        }

        checkIsOnCameraThread();

        mAutoFocusPending = false;
        if (!isReady() || mCamera == null || !mAutoFocus) {

            return;
        }

        long delay = 200;
        AutoFocusState nextState = mFocusState;
        try {
            switch (nextState) {
                case IDLE:
                    mCamera.cancelAutoFocus();
                    // Next step: start autoFocus in 100ms
                    delay = 100;
                    nextState = AutoFocusState.STARTING;
                    break;

                case STARTING:
                    mCamera.autoFocus(this);
                    // Next step: wait 2s until it succeeds
                    nextState = AutoFocusState.WAITING;
                    delay = AUTOFOCUS_DELAY_MS;
                    break;

                case SUCCESS:
                    // Next step: restart autofocus in 2s.
                    delay = AUTOFOCUS_DELAY_MS;
                    nextState = AutoFocusState.IDLE;
                    break;

                case WAITING:
                    delay = AUTOFOCUS_DELAY_MS;
                    break;

                case FAILED:
                default:
                    // Restart autofocus in 1s
                    nextState = AutoFocusState.IDLE;
                    delay = 1000;
                    break;
            }

        } catch (RuntimeException exception) {
            // A RuntimeException can be raised if the camera auto focus fails.
            Log.e(LOG_TAG, "Camera autofocus error: " + exception);
        }

        // Schedule a focus update and make sure there is only one such update in the camera handler queue.
        mFocusState = nextState;
        mAutoFocusPending = true;
        mCameraHandler.removeCallbacks(mAutoFocusHandler);
        mCameraHandler.postDelayed(mAutoFocusHandler, delay);
    }

    //
    // Private methods
    //

    @NonNull
    private Size findPictureSize(@NonNull final Camera.Parameters parameters) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findPictureSize");
        }

        checkIsOnCameraThread();

        List<Camera.Size> supportedPictureSizes = parameters.getSupportedPictureSizes();

        Camera.Size selectedPictureSize = null;
        int maxResolution = 0;
        for (Camera.Size supportedPictureSize : supportedPictureSizes) {
            int realWidth = supportedPictureSize.width;
            int realHeight = supportedPictureSize.height;
            int resolution = realWidth * realHeight;
            if (resolution < MIN_PREVIEW_PIXELS) {
                continue;
            }

            if (resolution > maxResolution) {
                maxResolution = resolution;
                selectedPictureSize = supportedPictureSize;
            }
        }

        if (selectedPictureSize == null) {
            selectedPictureSize = parameters.getPictureSize();
        }

        return new Size(selectedPictureSize.width, selectedPictureSize.height);
    }

    private void setCameraParameters(@NonNull final Camera.Parameters parameters) {
        if (DEBUG) {
            Log.d(LOG_TAG, "CameraManager.setCameraParameters");
        }

        checkIsOnCameraThread();

        if (mCamera == null) {

            return;
        }

        try {
            doSetTorch(parameters, mCurrentFlashMode);

            mCamera.setParameters(parameters);
        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }

        try {
            if (mMode == Mode.QRCODE) {
                List<String> supportedSceneModes = parameters.getSupportedSceneModes();
                if (supportedSceneModes != null && supportedSceneModes.contains(Camera.Parameters.SCENE_MODE_BARCODE)) {
                    parameters.setSceneMode(Camera.Parameters.SCENE_MODE_BARCODE);
                    mCamera.setParameters(parameters);
                }
            }

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }

        // Configure to use a dynamic FPS range
        try {
            final int[] fpsRange = getPreviewFpsRange(parameters.getSupportedPreviewFpsRange());
            if (fpsRange != null && mCamera != null) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Using FPS " + fpsRange[0] + ".." + fpsRange[1]);
                }
                parameters.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
                mCamera.setParameters(parameters);
            }

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }

        // In QRCODE mode, setup a small zoom so that we better see small qr-codes.
        // 1.5 seems reasonable for most devices (above it creates problems on some devices).
        try {
            if (mMode == Mode.QRCODE && parameters.isZoomSupported() && mCamera != null) {
                List<Integer> ratios = parameters.getZoomRatios();
                int zoom = 0;
                if (ratios != null) {
                    for (int i = 0; i < ratios.size(); i++) {
                        if (ratios.get(i) > 150) {
                            zoom = i;
                            break;
                        }
                    }
                } else {
                    zoom = parameters.getMaxZoom() / 10;

                    // Some phones have a high zoom factor and this is not good.
                    if (zoom > 4) {
                        zoom = 4;
                    }
                }

                if (DEBUG) {
                    Log.e(LOG_TAG, "Using camera zoom=" + zoom);
                }
                parameters.setZoom(zoom);
                mCamera.setParameters(parameters);
            }

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera zoom error: " + exception);
        }

        // Set the focus mode
        try {
            List<String> focusModes = parameters.getSupportedFocusModes();
            mAutoFocus = mMode == Mode.PHOTO;

            if (focusModes != null) {
                String focusMode = null;

                // The continuous picture is not always available.  There is a continuous video mode but
                // it is not as fast as the auto focus mode which works better.  The macro focus mode
                // can help on some devices but there is a risk.
                if (mMode == Mode.QRCODE && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
                    mAutoFocus = true;
                }

                if (focusMode == null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    focusMode = Camera.Parameters.FOCUS_MODE_AUTO;
                    mAutoFocus = true;
                }

                if (focusMode != null && !focusMode.equals(parameters.getFocusMode()) && mCamera != null) {

                    if (DEBUG) {
                        Log.e(LOG_TAG, "Setting camera focus with " + focusMode + " autoFocus=" + mAutoFocus);
                    }

                    parameters.setFocusMode(focusMode);
                    mCamera.setParameters(parameters);
                }
            }

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera focus error: " + exception);
        }

        // Set the preview size.
        try {
            parameters.setPreviewSize(mCameraResolution.width, mCameraResolution.height);
            mCamera.setParameters(parameters);

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }

        // Set the exposure and picture size.
        try {
            mExposureMin = parameters.getMinExposureCompensation();
            mExposureMax = parameters.getMaxExposureCompensation();
            parameters.setExposureCompensation(0);

            if (mMode == Mode.QRCODE) {
                final List<Integer> previewFormats = parameters.getSupportedPreviewFormats();
                for (Integer format : previewFormats) {
                    if (format == ImageFormat.YUY2) {
                        parameters.setPreviewFormat(format);
                        break;
                    }
                }

                parameters.setRecordingHint(true);
                mExposureValue = 0;
                mExposureCurrentMin = 0;
                mExposureCurrentMax = 0;
                mExposureIncrement = 1;
                mExposureCounter = NB_FRAMES_PER_EXPOSURE_CHECK;
            }

            parameters.setPictureSize(mCameraPictureResolution.width, mCameraPictureResolution.height);
            mCamera.setParameters(parameters);

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
        }

    }

    private void nextExposure() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextExposure");
        }

        if (mCamera == null || --mExposureCounter > 0) {
            return;
        }

        try {
            final Camera.Parameters parameters = mCamera.getParameters();

            mExposureValue += mExposureIncrement;
            mExposureCounter = NB_FRAMES_PER_EXPOSURE_CHECK;

            // If we the next exposure is out of the current range, grow or shrink the
            // current range depending on whether we are in the grow or shrink mode.
            // We switch to another mode when we have covered the full range.
            if (mExposureValue < mExposureCurrentMin) {
                if (mExposureGrow) {
                    if (mExposureCurrentMin > mExposureMin) {
                        mExposureCurrentMin--;
                    } else if (mExposureCurrentMax == mExposureMax) {
                        mExposureGrow = false;
                    }
                } else {
                    if (mExposureCurrentMin < 0) {
                        mExposureCurrentMin++;
                    } else if (mExposureCurrentMax == 0) {
                        mExposureGrow = true;
                    }
                }
                mExposureIncrement = 1;
            } else if (mExposureValue > mExposureCurrentMax) {
                if (mExposureGrow) {
                    if (mExposureCurrentMax < mExposureMax) {
                        mExposureCurrentMax++;
                    } else if (mExposureCurrentMin == mExposureMin) {
                        mExposureGrow = false;
                    }
                } else {
                    if (mExposureCurrentMax > 0) {
                        mExposureCurrentMax--;
                    } else if (mExposureCurrentMin == 0) {
                        mExposureGrow = true;
                    }
                }
                mExposureIncrement = -1;
            }

            if (DEBUG) {
                Log.e(LOG_TAG, "Using exposure " + mExposureValue);
            }
            parameters.setExposureCompensation(mExposureValue);
            mCamera.setParameters(parameters);

        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera error: " + exception);
            mExposureValue = 0;
            mExposureCurrentMin = 0;
            mExposureCurrentMax = 0;
        }
    }

    // For calculate the best fps range for still image capture.
    private final static int MAX_PREVIEW_FPS_TIMES_1000 = 400000;
    private final static int PREFERRED_PREVIEW_FPS_TIMES_1000 = 30000;

    // From Google Camera2 application CameraUtil class, get the correct configuration for FPS
    // range to reduce frame rate in dark conditions so that the auto focus can work.
    // See https://android.googlesource.com/platform/packages/apps/Camera2
    @Nullable
    private static int[] getPreviewFpsRange(@Nullable List<int[]> frameRates) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPreviewFpsRange");
        }

        if (frameRates == null || frameRates.isEmpty()) {
            return null;
        }

        // Find the lowest min rate in supported ranges who can cover 30fps.
        int lowestMinRate = MAX_PREVIEW_FPS_TIMES_1000;
        for (int[] rate : frameRates) {
            int minFps = rate[0];
            int maxFps = rate[1];
            if (maxFps >= PREFERRED_PREVIEW_FPS_TIMES_1000 &&
                    minFps <= PREFERRED_PREVIEW_FPS_TIMES_1000 &&
                    minFps < lowestMinRate) {
                lowestMinRate = minFps;
            }
        }

        // Find all the modes with the lowest min rate found above, the pick the
        // one with highest max rate.
        int resultIndex = -1;
        int highestMaxRate = 0;
        for (int i = 0; i < frameRates.size(); i++) {
            int[] rate = frameRates.get(i);
            int minFps = rate[0];
            int maxFps = rate[1];
            if (minFps == lowestMinRate && highestMaxRate < maxFps) {
                highestMaxRate = maxFps;
                resultIndex = i;
            }
        }

        if (resultIndex >= 0) {
            return frameRates.get(resultIndex);
        }
        return null;
    }

    @Override
    protected void onOrientationChanged(int degrees) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOrientationChanged");
        }

        checkIsOnCameraThread();

        if (mCamera == null || mCameraId < 0) {

            return;
        }

        int rotation;
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        try {
            Camera.getCameraInfo(mCameraId, cameraInfo);

        } catch (RuntimeException exception) {

            return;
        }

        if (mCameraFacing) {
            rotation = (cameraInfo.orientation + degrees) % 360;
            // compensate the mirror
            mCameraOrientation = (360 - rotation) % 360;
        } else { // back-facing
            rotation = (cameraInfo.orientation - degrees + 360) % 360;
            mCameraOrientation = rotation;
        }

        if (rotation == 0) {
            rotation = 180;
        } else if (rotation == 180) {
            rotation = 0;
        }
        if (DEBUG) {
            Log.e(LOG_TAG, "Degrees " + degrees + " set camera display orientation " + mCameraOrientation + " with rotation " + rotation);
        }
        try {
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setRotation(rotation);
            mCamera.setParameters(parameters);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "onOrientationChanged - camera parameter update failed", exception);
        }

        // After camera rotation change, trigger the auto focus again.
        mFocusState = AutoFocusState.IDLE;
        updateAutoFocusState();
    }

    private void doSetTorch(@NonNull Camera.Parameters parameters, @NonNull FlashMode newSetting) {
        if (DEBUG) {
            Log.d(LOG_TAG, "doSetTorch: parameters=" + parameters + " newSetting=" + newSetting);
        }

        checkIsOnCameraThread();

        String flashMode;
        List<String> modes = parameters.getSupportedFlashModes();
        switch (newSetting) {
            case ON:
                flashMode = findSettableValue(modes, Camera.Parameters.FLASH_MODE_TORCH, Camera.Parameters.FLASH_MODE_ON);
                break;

            case OFF:
                flashMode = findSettableValue(modes, Camera.Parameters.FLASH_MODE_OFF);
                break;

            case SINGLE:
            default:
                if (mMode == Mode.QRCODE) {
                    flashMode = findSettableValue(modes, Camera.Parameters.FLASH_MODE_TORCH, Camera.Parameters.FLASH_MODE_ON);
                } else {
                    flashMode = findSettableValue(modes, Camera.Parameters.FLASH_MODE_RED_EYE, Camera.Parameters.FLASH_MODE_ON);
                }
                break;
        }
        if (flashMode != null) {
            // Bug on some devices, where the torch does not switch off when changing modes.
            // It must be switch off and a correct mode set after some delay.
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            if (mCamera != null) {
                mCamera.setParameters(parameters);
            }

            if (!flashMode.equals(Camera.Parameters.FLASH_MODE_OFF)) {
                mCameraHandler.postDelayed(() -> {
                    if (mCamera != null) {
                        parameters.setFlashMode(flashMode);
                        mCamera.setParameters(parameters);

                        // In QR-code single mode, the Red-Eye does not function since we must trigger a capture
                        // Instead, turn OFF the flash after 500ms after the torch was ON.  Do this by using setTorch()
                        // so that we update the mCurrentFlashMode and other calls will work.
                        if (mMode == Mode.QRCODE && newSetting == FlashMode.SINGLE) {
                            mCameraHandler.postDelayed(() -> setTorch(FlashMode.OFF), 500);
                        }
                    }
                }, 100);
            }
        }
    }

    private void doSetZoom(Camera.Parameters parameters, int zoom) {
        if (DEBUG) {
            Log.d(LOG_TAG, "doSetZoom: parameters=" + parameters + "zoom=" + zoom);
        }

        try {
            if (parameters.isZoomSupported()) {
                parameters.setZoom(zoom);
                if (mCamera != null) {
                    mCamera.setParameters(parameters);
                }
            }
        } catch (Exception exception) {
            Log.d(LOG_TAG, "Camera zoom error: " + exception);
        }
    }

    private String findSettableValue(Collection<String> supportedValues, String... desiredValues) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findSettableValue: supportedValues=" + supportedValues + " desiredValues=" + Arrays.toString(desiredValues));
        }

        String result = null;
        if (supportedValues != null) {
            for (String desiredValue : desiredValues) {
                if (supportedValues.contains(desiredValue)) {
                    result = desiredValue;
                    break;
                }
            }
        }

        return result;
    }

    private boolean prepareVideoRecorder() {
        if (DEBUG) {
            Log.d(LOG_TAG, "prepareVideoRecorder");
        }

        checkIsOnCameraThread();

        // The camera can be closed, in that case ignore the prepare video.
        if (mCamera == null || mCameraId < 0) {

            return false;
        }

        mMediaRecorder = new MediaRecorder();

        try {
            mCamera.unlock();

            mMediaRecorder.setCamera(mCamera);
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            CamcorderProfile profile = null;

            // Don't even try to use 1080x1920 on low ram and old devices: it freezes and
            // the only solution is to unplug the battery!
            if (!isLowRamDevice()) {
                profile = CamcorderProfile.get(CamcorderProfile.QUALITY_1080P);
            }

            if (profile == null) {
                profile = CamcorderProfile.get(CamcorderProfile.QUALITY_720P);
                if (profile == null) {
                    profile = CamcorderProfile.get(CamcorderProfile.QUALITY_480P);
                    if (profile == null) {
                        profile = CamcorderProfile.get(CamcorderProfile.QUALITY_LOW);
                    }
                }
            }
            mMediaRecorder.setProfile(profile);

            int orientationHint = mCameraOrientation;
            if (!mCameraFacing) {
                if (orientationHint == 0) {
                    orientationHint = 180;
                } else if (orientationHint == 180) {
                    orientationHint = 0;
                }
            } else {
                orientationHint = 270;
            }

            mMediaRecorder.setOrientationHint(orientationHint);

        } catch (RuntimeException ex) {
            // Many problems can be raised by the above media recorder APIs
            releaseMediaRecorder();
            return false;
        }

        try {
            mVideoFile = File.createTempFile("camera", ".mp4", mActivity.getCacheDir());
            mMediaRecorder.setOutputFile(mVideoFile.getPath());
        } catch (IOException e) {
            Log.d(LOG_TAG, "IO error when creating file", e);
            if (mVideoFile != null) {
                Utils.deleteFile(LOG_TAG, mVideoFile);
                mVideoFile = null;
            }
            releaseMediaRecorder();
            return false;
        }

        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException exception) {
            if (Logger.DEBUG) {
                Logger.debug(LOG_TAG, "IllegalStateException preparing MediaRecorder: ", exception);
            }
            releaseMediaRecorder();
            return false;
        } catch (IOException exception) {
            if (Logger.ERROR) {
                Logger.error(LOG_TAG, "IO exception", exception);
            }
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private void releaseMediaRecorder() {
        if (DEBUG) {
            Log.d(LOG_TAG, "releaseMediaRecorder");
        }

        checkIsOnCameraThread();

        if (mMediaRecorder != null) {
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;

            if (mCamera != null) {
                mCamera.lock();
            }
        }
    }

    public boolean isLowRamDevice() {

        ActivityManager activityManager = (ActivityManager) mActivity.getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager != null) {
            return activityManager.isLowRamDevice();
        }

        return true;
    }
}
