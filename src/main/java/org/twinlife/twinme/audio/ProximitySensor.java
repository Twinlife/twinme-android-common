/*
 *  Copyright (c) 2019-2020 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 */

//
// based from : examples/androidapp/src/org/appspot/apprtc/AppRTCProximitySensor.java
//  WebRTC 72:   7cec6ebed55b84cd2223a86d16e233aadba78857
//

/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.twinlife.twinme.audio;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.annotation.Nullable;

import org.webrtc.ThreadUtils;

/**
 * ProximitySensor manages functions related to the proximity sensor in the application.
 * On most device, the proximity sensor is implemented as a boolean-sensor.
 * It returns just two values "NEAR" or "FAR". Thresholding is done on the LUX
 * value i.e. the LUX value of the light sensor is compared with a threshold.
 * A LUX-value more than the threshold means the proximity sensor returns "FAR".
 * Anything less than the threshold value and the sensor  returns "NEAR".
 */
public class ProximitySensor implements SensorEventListener {
    private static final String LOG_TAG = "ProximitySensor";
    private static final boolean DEBUG = false;

    // This class should be created, started and stopped on one thread
    // (e.g. the main thread). We use |nonThreadSafe| to ensure that this is
    // the case. Only active when |DEBUG| is set to true.
    private final ThreadUtils.ThreadChecker threadChecker = new ThreadUtils.ThreadChecker();

    private final Runnable onSensorStateListener;
    private final SensorManager sensorManager;
    @Nullable
    private Sensor proximitySensor;
    private boolean lastStateReportIsNear;

    /**
     * Construction
     */
    public static ProximitySensor create(Context context, Runnable sensorStateListener) {
        if (DEBUG) {
            Log.d(LOG_TAG, "create: context=" + context + " sensorStateListener=" + sensorStateListener);
        }

        return new ProximitySensor(context, sensorStateListener);
    }

    private ProximitySensor(Context context, Runnable sensorStateListener) {
        if (DEBUG) {
            Log.d(LOG_TAG, "context=" + context + " sensorStateListener=" + sensorStateListener);
        }

        onSensorStateListener = sensorStateListener;
        sensorManager = ((SensorManager) context.getSystemService(Context.SENSOR_SERVICE));
    }

    /**
     * Activate the proximity sensor. Also do initialization if called for the
     * first time.
     */
    public boolean start() {
        if (DEBUG) {
            Log.d(LOG_TAG, "start");
        }

        threadChecker.checkIsOnValidThread();
        if (!initDefaultSensor()) {
            // Proximity sensor is not supported on this device.
            return false;
        }
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        return true;
    }

    /**
     * Deactivate the proximity sensor.
     */
    public void stop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stop");
        }

        threadChecker.checkIsOnValidThread();
        if (proximitySensor == null) {
            return;
        }
        sensorManager.unregisterListener(this, proximitySensor);
    }

    /**
     * Getter for last reported state. Set to true if "near" is reported.
     */
    public boolean sensorReportsNearState() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sensorReportsNearState");
        }

        threadChecker.checkIsOnValidThread();
        return lastStateReportIsNear;
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAccuracyChanged sensor= " + sensor + " accuracy=" + accuracy);
        }

        threadChecker.checkIsOnValidThread();
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.e(LOG_TAG, "The values returned by this sensor cannot be trusted");
        }
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSensorChanged event= " + event);
        }

        threadChecker.checkIsOnValidThread();
        // As a best practice; do as little as possible within this method and
        // avoid blocking.
        float distanceInCentimeters = event.values[0];
        if (proximitySensor != null && distanceInCentimeters < proximitySensor.getMaximumRange()) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Proximity sensor => NEAR state");
            }
            lastStateReportIsNear = true;
        } else {
            if (DEBUG) {
                Log.d(LOG_TAG, "Proximity sensor => FAR state");
            }
            lastStateReportIsNear = false;
        }

        // Report about new state to listening client. Client can then call
        // sensorReportsNearState() to query the current state (NEAR or FAR).
        if (onSensorStateListener != null) {
            onSensorStateListener.run();
        }
    }

    /**
     * Get default proximity sensor if it exists. Tablet devices (e.g. Nexus 7)
     * does not support this type of sensor and false will be returned in such
     * cases.
     */
    private boolean initDefaultSensor() {
        if (DEBUG) {
            Log.d(LOG_TAG, "initDefaultSensor");
        }

        if (proximitySensor != null) {
            return true;
        }
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor == null) {
            return false;
        }
        logProximitySensorInfo();
        return true;
    }

    /**
     * Helper method for logging information about the proximity sensor.
     */
    private void logProximitySensorInfo() {
        if (DEBUG) {
            Log.d(LOG_TAG, "logProximitySensorInfo");
        }

        if (proximitySensor == null) {
            return;
        }
        StringBuilder info = new StringBuilder("Proximity sensor: ");
        info.append("name=").append(proximitySensor.getName());
        info.append(", vendor: ").append(proximitySensor.getVendor());
        info.append(", power: ").append(proximitySensor.getPower());
        info.append(", resolution: ").append(proximitySensor.getResolution());
        info.append(", max range: ").append(proximitySensor.getMaximumRange());
        info.append(", min delay: ").append(proximitySensor.getMinDelay());
        info.append(", type: ").append(proximitySensor.getStringType());
        info.append(", max delay: ").append(proximitySensor.getMaxDelay());
        info.append(", reporting mode: ").append(proximitySensor.getReportingMode());
        info.append(", isWakeUpSensor: ").append(proximitySensor.isWakeUpSensor());

        if (DEBUG) {
            Log.d(LOG_TAG, info.toString());
        }
    }
}
