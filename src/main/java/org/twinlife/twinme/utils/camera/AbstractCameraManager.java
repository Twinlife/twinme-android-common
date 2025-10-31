/*
 *  Copyright (c) 2021-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Abstract camera manager providing operations used by the Camera 1 and Camera 2 manager implementations.
 * <p>
 * The camera manager (1 & 2) is using a dedicated thread to perform all camera operations.
 * The following operations are asynchronous:
 * <p>
 * - open()
 * - close()
 * - takePicture()
 * - switchCamera()
 * - setTorch()
 * <p>
 * and they are queued and executed by the Camera Thread.
 * <p>
 * The caller is informed about the result through the CameraManager.CameraCallback interface.
 * <p>
 * The Camera1ManagerImpl uses the Camera 1 API and the Camera2ManagerImpl uses the Camera 2 API.
 * Both hide the details of the Camera API and provide a common API.
 */
public abstract class AbstractCameraManager implements CameraManager {
    private static final String LOG_TAG = "AbstractCameraManager";
    private static final boolean DEBUG = false;
    protected static final int MIN_PREVIEW_PIXELS = 480 * 320; // normal screen
    protected static final double MAX_ASPECT_DISTORTION = 0.24; // 0.15;

    protected void checkIsOnCameraThread() {

        if (Thread.currentThread() != mCameraThread) {
            throw new IllegalStateException("Wrong thread");
        }
    }

    protected class OrientationListener extends OrientationEventListener {
        private int mDisplayOrientation = 0;

        OrientationListener(@NonNull Context context) {

            super(context);
        }

        public void onOrientationChanged(int orientation) {

            int displayOrientation;
            if (orientation <= 45) {
                displayOrientation = 0;
            } else if (orientation <= 90 + 45) {
                displayOrientation = 90;
            } else if (orientation <= 180 + 45) {
                displayOrientation = 180;
            } else if (orientation <= 270 + 45) {
                displayOrientation = 270;
            } else {
                displayOrientation = 0;
            }

            if (mDisplayOrientation != displayOrientation) {
                mDisplayOrientation = displayOrientation;
                mCameraHandler.post(() -> AbstractCameraManager.this.onOrientationChanged(displayOrientation));
            }
        }
    }

    @NonNull
    protected final CameraThread mCameraThread;
    @NonNull
    protected final Handler mCameraHandler;
    @NonNull
    protected final Activity mActivity;
    @NonNull
    protected final TextureView mTextureView;
    @NonNull
    protected final CameraCallback mCameraCallback;
    @NonNull
    protected final Mode mMode;
    @NonNull
    protected final Point mScreenResolution;
    @NonNull
    protected State mState;
    protected Size mCameraResolution;
    protected Size mCameraPictureResolution;
    protected boolean mAskedCameraFacing;
    protected boolean mCameraFacing;
    protected int mCameraOrientation;
    protected int mMaxZoom;
    @NonNull
    protected FlashMode mCurrentFlashMode;
    protected int mCurrentZoom;

    @Nullable
    protected SurfaceTexture mSurfaceTexture;
    @NonNull
    protected final OrientationListener mOrientationListener;

    protected AbstractCameraManager(@NonNull Activity activity, @NonNull TextureView textureView,
                                    @NonNull CameraCallback cameraCallback, @NonNull Mode mode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "Camera1ManagerImpl");
        }

        mState = State.UNINITIALIZED;
        mCameraThread = CameraThread.getInstance();

        mActivity = activity;
        mTextureView = textureView;
        mCameraCallback = cameraCallback;
        mMode = mode;
        mCurrentFlashMode = FlashMode.OFF;
        mCurrentZoom = 0;

        Display display = mActivity.getWindowManager().getDefaultDisplay();
        Point theScreenResolution = new Point();
        display.getSize(theScreenResolution);
        mScreenResolution = theScreenResolution;

        mCameraHandler = mCameraThread.getHandler();
        mOrientationListener = new OrientationListener(mActivity);
    }

    @Override
    public final synchronized boolean isOpened() {

        return mState == State.READY || mState == State.WAITING_PICTURE;
    }

    @Override
    public final Size getCameraResolution() {

        return mCameraResolution;
    }

    @Override
    public final boolean isCameraFacingFront() {

        return mCameraFacing;
    }

    @Override
    public final int getDisplayOrientation() {

        return mCameraOrientation;
    }

    public final int getMaxZoom() {

        return mMaxZoom;
    }

    protected synchronized boolean isReady() {

        return mState == State.READY;
    }

    @Override
    public void open(@NonNull SurfaceTexture surfaceTexture, boolean backCamera) {
        if (DEBUG) {
            Log.d(LOG_TAG, "open: surfaceTexture=" + surfaceTexture + " backCamera=" + backCamera);
        }

        // We have to make sure there is only one openInternal() that is executed.
        // To switch to another camera, we can do another call to open().
        synchronized (this) {
            if (mState == State.STOPPING || mState == State.STARTING) {

                return;
            }

            if (mState != State.UNINITIALIZED && backCamera == mAskedCameraFacing) {

                return;
            }

            mState = State.STARTING;
            mAskedCameraFacing = backCamera;
            mSurfaceTexture = surfaceTexture;
        }

        mCameraHandler.post(this::openInternal);
    }

    protected abstract void openInternal();

    @Override
    public void close() {
        if (DEBUG) {
            Log.d(LOG_TAG, "close");
        }

        // Stop preview from the current thread so that we stop sending the camera stream to the texture that is being destroyed.
        stopPreview();

        // We have to make sure there is only one closeInternal() that is executed.
        synchronized (this) {
            if (mState == State.STOPPING) {

                return;
            }

            mState = State.STOPPING;
        }

        // But close the camera asynchronously to avoid blocking the main UI thread.
        mCameraHandler.post(this::closeInternal);
    }

    protected abstract void stopPreview();

    protected abstract void closeInternal();

    protected abstract void onOrientationChanged(int displayOrientation);

    @Override
    public void takePicture() {
        if (DEBUG) {
            Log.d(LOG_TAG, "takePicture");
        }

        if (isReady()) {
            mCameraHandler.post(this::takePictureInternal);
        }
    }

    protected abstract void takePictureInternal();

    public void startRecordVideo() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startRecordVideo");
        }

        if (isReady()) {
            mCameraHandler.post(this::startRecordVideoInternal);
        }
    }

    protected abstract void startRecordVideoInternal();

    public void stopRecordVideo() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopRecordVideo");
        }

        if (isReady()) {
            mCameraHandler.post(this::stopRecordVideoInternal);
        }
    }


    protected abstract void stopRecordVideoInternal();

    @Override
    public synchronized void setTorch(@NonNull FlashMode mode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setTorch mode=" + mode);
        }

        if (mCurrentFlashMode != mode) {
            mCurrentFlashMode = mode;
            if (isOpened()) {
                mCameraHandler.post(this::setTorchInternal);
            }
        }
    }

    protected abstract void setTorchInternal();

    @Override
    public synchronized void setZoom(int zoom) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setZoom zoom=" + zoom);
        }

        if (mCurrentZoom != zoom) {
            mCurrentZoom = zoom;
            if (isOpened()) {
                mCameraHandler.post(this::setZoomInternal);
            }
        }
    }

    protected abstract void setZoomInternal();

    protected void setSizes() {
        if (DEBUG) {
            Log.d(LOG_TAG, "setSizes");
        }

        checkIsOnCameraThread();

        if (mCameraResolution != null) {
            int videoWidth;
            int videoHeight;
            if (mCameraOrientation != 90 && mCameraOrientation != 270) {
                videoWidth = mCameraResolution.width;
                videoHeight = mCameraResolution.height;
            } else {
                //noinspection SuspiciousNameCombination
                videoWidth = mCameraResolution.height;
                //noinspection SuspiciousNameCombination
                videoHeight = mCameraResolution.width;
            }

            double aspectRatio = (double) videoHeight / videoWidth;
            int width = mTextureView.getWidth();
            int height = mTextureView.getHeight();
            int newWidth;
            int newHeight;
            if (height > (int) (width * aspectRatio)) {
                newWidth = (int) (height / aspectRatio);
                newHeight = height;
            } else {
                newWidth = width;
                newHeight = (int) (width * aspectRatio);
            }
            int offsetX = (width - newWidth) / 2;
            int offsetY = (height - newHeight) / 2;
            float scaleX = (float) newWidth / width;
            float scaleY = (float) newHeight / height;

            Matrix transform = new Matrix();
            mTextureView.getTransform(transform);

            if (DEBUG) {
                Log.e(LOG_TAG, "Texture w=" + width + " h=" + height + " scalex=" + scaleX + " scaleY=" + scaleY + " offsetX=" + offsetX + " offsetY=" + offsetY);
            }

            transform.setScale(scaleX, scaleY);
            transform.postTranslate(offsetX, offsetY);
            mActivity.runOnUiThread(() -> mTextureView.setTransform(transform));
        }
    }

    @NonNull
    protected Size findPreviewSize(@NonNull final Size[] supportedPreviewSizes, int width, int height, @Nullable Size defaultPreviewSize) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findPreviewSize width=" + width + " height=" + height + " defaultPreviewSize=" + defaultPreviewSize);
        }

        double screenAspectRatio = mScreenResolution.y / (double) mScreenResolution.x;

        Size selectedPreviewSize = null;
        int viewResolution = computeMaxResolution(width, height);
        int maxResolution = 0;
        int defaultPreviewResolution = 0;

        if (defaultPreviewSize != null) {
            defaultPreviewResolution = defaultPreviewSize.width * defaultPreviewSize.height;
        }
        for (Size supportedPreviewSize : supportedPreviewSizes) {
            int realWidth = supportedPreviewSize.width;
            int realHeight = supportedPreviewSize.height;
            int resolution = realWidth * realHeight;
            if (resolution < MIN_PREVIEW_PIXELS) {
                continue;
            }

            // Choose a resolution that has an aspect ratio close to the screen.
            if (mMode == Mode.QRCODE) {
                boolean isCandidatePortrait = realWidth < realHeight;
                int maybeFlippedWidth = isCandidatePortrait ? realHeight : realWidth;
                int maybeFlippedHeight = isCandidatePortrait ? realWidth : realHeight;
                double aspectRatio = maybeFlippedWidth / (double) maybeFlippedHeight;
                double distortion = Math.abs(aspectRatio - screenAspectRatio);
                if (DEBUG) {
                    Log.d(LOG_TAG, "Camera w=" + realWidth + " h=" + realHeight + " dist=" + distortion
                            + " aspect=" + aspectRatio + " screenAspect=" + screenAspectRatio);
                }

                if (defaultPreviewResolution < resolution) {
                    defaultPreviewSize = supportedPreviewSize;
                    defaultPreviewResolution = resolution;
                }

                if (distortion > MAX_ASPECT_DISTORTION) {
                    continue;
                }

                if (maybeFlippedWidth == mScreenResolution.x && maybeFlippedHeight == mScreenResolution.y) {
                    Size exactPoint = new Size(realWidth, realHeight);
                    if (DEBUG) {
                        Log.d(LOG_TAG, "Found preview size exactly matching screen size: " + exactPoint);
                    }
                    return exactPoint;
                }
            }

            if (resolution > maxResolution && (mMode == Mode.PHOTO || resolution <= viewResolution)) {
                maxResolution = resolution;
                selectedPreviewSize = supportedPreviewSize;
            }
        }

        if (selectedPreviewSize == null) {
            selectedPreviewSize = defaultPreviewSize;
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "Selected camera " + selectedPreviewSize.width + "x" + selectedPreviewSize.height);
        }
        return new Size(selectedPreviewSize.width, selectedPreviewSize.height);
    }

    static protected int computeMaxResolution(int width, int height) {
        if (DEBUG) {
            Log.d(LOG_TAG, "computeMaxResolution width=" + width + " height=" + height);
        }

        if (width == 0) {
            return 1024 * 768;
        }

        if (width < 1024) {
            height = (height * 1024) / width;
            width = 1024;
        }

        return width * height;
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    @NonNull
    protected static Size chooseOptimalSize(@NonNull Size[] choices, int textureViewWidth,
                                            int textureViewHeight, int maxWidth, int maxHeight, @NonNull Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.width;
        int h = aspectRatio.height;

        for (Size option : choices) {
            if (option.width <= maxWidth && option.height <= maxHeight && option.height == option.width * h / w) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());

        } else if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());

        } else {
            if (DEBUG) {
                Log.e(LOG_TAG, "Couldn't find any suitable preview size");
            }
            return choices[0];
        }
    }

    protected static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(@NonNull Size lhs, @NonNull Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.width * lhs.height - (long) rhs.width * rhs.height);
        }

    }
}
