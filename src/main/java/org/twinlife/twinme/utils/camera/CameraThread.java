/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils.camera;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;

/**
 * Camera thread that handles all camera operations.
 * <p>
 * There is only one instance of the CameraThread and it is created the first time the getInstance() method is called.
 * Each time the camera is release, the CameraThread.release() method must be called.  The CameraThread will be destroyed
 * 5 second after the last release.
 */
class CameraThread extends Thread {
    private static final int SHUTDOWN_DELAY = 5000;
    private static volatile CameraThread sCameraThread;
    private static final Object sLock = new Object();
    private Handler mHandler;
    private int mUseCounter;
    private final CountDownLatch mHandlerInitLatch;

    static CameraThread getInstance() {

        CameraThread cameraThread;
        synchronized (sLock) {
            cameraThread = sCameraThread;
            if (cameraThread == null) {
                sCameraThread = new CameraThread();
                sCameraThread.start();
                cameraThread = sCameraThread;
            }

            cameraThread.mUseCounter++;
        }
        return cameraThread;
    }

    void release() {

        synchronized (sLock) {
            mUseCounter--;
            if (mUseCounter == 0) {
                mHandler.postDelayed(this::checkRelease, SHUTDOWN_DELAY);
            }
        }
    }

    Handler getHandler() {

        try {
            mHandlerInitLatch.await();

        } catch (InterruptedException ie) {
            // continue?
        }
        return mHandler;
    }

    @Override
    public void run() {

        Looper.prepare();
        mHandler = new Handler();
        mHandlerInitLatch.countDown();
        Looper.loop();
    }

    private void checkRelease() {

        synchronized (sLock) {
            if (mUseCounter == 0) {
                mHandler.getLooper().quit();
                sCameraThread = null;
            }
        }
    }

    private CameraThread() {

        mHandlerInitLatch = new CountDownLatch(1);
        mUseCounter = 0;
        setName("CameraThread");
    }
}
