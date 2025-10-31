/*
 *  Copyright (c) 2019-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

//
// based from : examples/androidapp/src/org/appspot/apprtc/AppRTCBluetoothManager.java
//  WebRTC 72:   7cec6ebed55b84cd2223a86d16e233aadba78857
//

/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.twinlife.twinme.audio;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.webrtc.ThreadUtils;

import java.util.List;
import java.util.Set;

/**
 * ProximitySensor manages functions related to Bluetoth devices in the application.
 */
public class LegacyBluetoothManager implements BluetoothManager {
    private static final String LOG_TAG = "BluetoothManager";
    private static final boolean DEBUG = false;

    // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;
    // Delay to wait before informing a device change after audio disconnect.
    private static final int BLUETOOTH_RECONNECT_DELAY_MS = 500;
    // Maximum number of SCO connection attempts.
    private static final int MAX_SCO_CONNECTION_ATTEMPTS = 2;

    private final Context context;
    private final TwinmeAudioManager twinmeAudioManager;
    @Nullable
    private final AudioManager audioManager;
    private final Handler handler;

    private int scoConnectionAttempts;
    private State bluetoothState;
    private int currentProfileConnectionState;
    private long bluetoothAudioDisconnectTime;
    private final BluetoothProfile.ServiceListener bluetoothServiceListener;
    @Nullable
    private BluetoothAdapter bluetoothAdapter;
    @Nullable
    private BluetoothHeadset bluetoothHeadset;
    @Nullable
    private BluetoothDevice bluetoothDevice;
    private final BroadcastReceiver bluetoothHeadsetReceiver;

    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private final Runnable bluetoothTimeoutRunnable = this::bluetoothTimeout;

    /**
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private class BluetoothServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) {
                Log.d(LOG_TAG, "BluetoothServiceListener.onServiceConnected =" + profile);
            }

            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return;
            }
            if (DEBUG) {
                Log.d(LOG_TAG, "BluetoothServiceListener.onServiceConnected: BT state=" + bluetoothState);
            }
            // Android only supports one connected Bluetooth Headset at a time.
            bluetoothHeadset = (BluetoothHeadset) proxy;
            updateAudioDeviceState();
            if (DEBUG) {
                Log.d(LOG_TAG, "onServiceConnected done: BT state=" + bluetoothState);
            }
        }

        @Override
        /* Notifies the client when the proxy object has been disconnected from the service. */
        public void onServiceDisconnected(int profile) {
            if (DEBUG) {
                Log.d(LOG_TAG, "BluetoothServiceListener.onServiceDisconnected =" + profile);
            }

            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return;
            }

            if (DEBUG) {
                Log.d(LOG_TAG, "BluetoothServiceListener.onServiceDisconnected: BT state=" + bluetoothState);
            }
            stopBluetoothAudio();
            bluetoothHeadset = null;
            bluetoothDevice = null;
            bluetoothState = State.HEADSET_UNAVAILABLE;
            updateAudioDeviceState();
            if (DEBUG) {
                Log.d(LOG_TAG, "onServiceDisconnected done: BT state=" + bluetoothState);
            }
        }
    }

    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private class BluetoothHeadsetBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(LOG_TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: context=" + context + " intent=" + intent);
            }

            if (bluetoothState == State.UNINITIALIZED) {
                return;
            }
            final String action = intent.getAction();
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action != null && action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                final int state =
                        intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    scoConnectionAttempts = 0;
                    updateAudioDeviceState();
                } else //noinspection StatementWithEmptyBody
                    if (state == BluetoothHeadset.STATE_CONNECTING) {
                        // No action needed.
                    } else //noinspection StatementWithEmptyBody
                        if (state == BluetoothHeadset.STATE_DISCONNECTING) {
                            // No action needed.
                        } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                            // Bluetooth is probably powered off during the call.
                            stopBluetoothAudio();
                            updateAudioDeviceState();
                        }
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action != null && action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                final int state = intent.getIntExtra(
                        BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer();
                    if (bluetoothState == State.SCO_CONNECTING) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "+++ Bluetooth audio SCO is now connected");
                        }
                        bluetoothState = State.SCO_CONNECTED;
                        scoConnectionAttempts = 0;
                        updateAudioDeviceState();

                        // Detect a short disconnect+reconnect to the audio SCO, when the interruption is less
                        // then 5s, simulate a button press.  It will be handled only if the TwinmeAudioManager
                        // has not received a button press during that same timeframe (+1 s to be sure).
                        if (bluetoothAudioDisconnectTime > 0) {
                            long now = System.currentTimeMillis();
                            if (now - bluetoothAudioDisconnectTime < SHORT_AUDIO_DISCONNECT_TIME) {
                                KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK);
                                twinmeAudioManager.sendButtonEvent(event, SHORT_AUDIO_DISCONNECT_TIME + 1000);
                            }
                            bluetoothAudioDisconnectTime = 0;
                        }
                    } else {
                        Log.w(LOG_TAG, "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED");
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "+++ Bluetooth audio SCO is now connecting...");
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "+++ Bluetooth audio SCO is now disconnected");
                    }
                    if (isInitialStickyBroadcast()) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Ignore STATE_AUDIO_DISCONNECTED initial sticky broadcast.");
                        }
                        return;
                    }

                    // Record when we disconnected to simulate a button press when we are connected again
                    // if the disconnection time is short (< 5s).
                    if (bluetoothState == State.SCO_CONNECTED) {
                        bluetoothAudioDisconnectTime = System.currentTimeMillis();
                    }

                    // Update the Bluetooth state because the audio is now disconnected.
                    bluetoothState = State.HEADSET_AVAILABLE;
                    updateAudioDeviceState();
                }
            }
            if (DEBUG) {
                Log.d(LOG_TAG, "onReceive done: BT state=" + bluetoothState);
            }
        }
    }

    /**
     * Construction.
     */
    static LegacyBluetoothManager create(Context context, TwinmeAudioManager audioManager) {
        return new LegacyBluetoothManager(context, audioManager);
    }

    private LegacyBluetoothManager(Context context, TwinmeAudioManager audioManager) {
        ThreadUtils.checkIsOnMainThread();
        this.context = context;
        twinmeAudioManager = audioManager;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        bluetoothState = State.UNINITIALIZED;
        bluetoothServiceListener = new BluetoothServiceListener();
        bluetoothHeadsetReceiver = new BluetoothHeadsetBroadcastReceiver();
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Returns the internal state.
     */
    public State getState() {
        ThreadUtils.checkIsOnMainThread();
        return bluetoothState;
    }

    @Nullable
    @Override
    public String getDeviceName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(context.checkPermission(android.Manifest.permission.BLUETOOTH_CONNECT, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)) {
                Log.w(LOG_TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH_CONNECT permission");
                return null;
            }
        } else {
            if (!(context.checkPermission(android.Manifest.permission.BLUETOOTH, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)) {
                Log.w(LOG_TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission");
                return null;
            }
        }

        if (bluetoothHeadset == null) {
            return null;
        }

        List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
        if (devices.isEmpty()) {
            return null;
        }

        return devices.get(0).getName();
    }

    /**
     * Activates components required to detect Bluetooth devices and to enable
     * BT SCO (audio is routed via BT SCO) for the headset profile. The end
     * state will be HEADSET_UNAVAILABLE but a state machine has started which
     * will start a state change sequence where the final outcome depends on
     * if/when the BT headset is enabled.
     * Example of state change sequence when start() is called while BT device
     * is connected and enabled:
     * UNINITIALIZED --> HEADSET_UNAVAILABLE --> HEADSET_AVAILABLE -->
     * SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO.
     * Note that the TwinmeAudioManager is also involved in driving this state
     * change.
     */
    public void start() {
        if (DEBUG) {
            Log.d(LOG_TAG, "start");
        }
        ThreadUtils.checkIsOnMainThread();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(context.checkPermission(android.Manifest.permission.BLUETOOTH_CONNECT, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)) {
                Log.w(LOG_TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH_CONNECT permission");
                return;
            }
        } else {
            if (!(context.checkPermission(android.Manifest.permission.BLUETOOTH, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)) {
                Log.w(LOG_TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission");
                return;
            }
        }
        if (audioManager == null) {
            Log.w(LOG_TAG, "audioManager is null");
            return;
        }
        if (bluetoothState != State.UNINITIALIZED) {
            Log.w(LOG_TAG, "Invalid BT state");
            return;
        }
        bluetoothHeadset = null;
        bluetoothDevice = null;
        scoConnectionAttempts = 0;

        // Get a handle to the default local Bluetooth adapter.
        android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            bluetoothAdapter = manager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Log.w(LOG_TAG, "Device does not support Bluetooth");
            return;
        }
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            Log.e(LOG_TAG, "Bluetooth SCO audio is not available off call");
            return;
        }
        logBluetoothAdapterInfo(bluetoothAdapter);
        try {
            // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
            // Hands-Free) proxy object and install a listener.
            if (!bluetoothAdapter.getProfileProxy(context, bluetoothServiceListener, BluetoothProfile.HEADSET)) {
                Log.e(LOG_TAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed");
                return;
            }
            // Register receivers for BluetoothHeadset change notifications.
            IntentFilter bluetoothHeadsetFilter = new IntentFilter();
            // Register receiver for change in connection state of the Headset profile.
            bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            // Register receiver for change in audio connection state of the Headset profile.
            bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            ContextCompat.registerReceiver(context, bluetoothHeadsetReceiver, bluetoothHeadsetFilter, ContextCompat.RECEIVER_EXPORTED);
            bluetoothState = State.HEADSET_UNAVAILABLE;
            currentProfileConnectionState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
            if (DEBUG) {
                Log.d(LOG_TAG, "HEADSET profile state: " + stateToString(currentProfileConnectionState));
            }
            if (currentProfileConnectionState == BluetoothAdapter.STATE_CONNECTED) {
                bluetoothState = State.HEADSET_AVAILABLE;
                Log.d(LOG_TAG, "Bluetooth proxy for headset profile has started");
            }
        } catch (Exception ignored) {
            // getProfileProxy() may also raise some SecurityException
            bluetoothState = State.ERROR;
        }
        if (DEBUG) {
            Log.d(LOG_TAG, "start done: BT state=" + bluetoothState);
        }
    }

    /**
     * Stops and closes all components related to Bluetooth audio.
     */
    public void stop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stop: BT state=" + bluetoothState);
        }
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothAdapter == null) {
            return;
        }
        // Stop BT SCO connection with remote device if needed.
        stopBluetoothAudio();
        // Close down remaining BT resources.
        if (bluetoothState == State.UNINITIALIZED) {
            return;
        }
        context.unregisterReceiver(bluetoothHeadsetReceiver);
        cancelTimer();
        if (bluetoothHeadset != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            } catch (Exception ignored) {

            }
            bluetoothHeadset = null;
        }
        bluetoothAdapter = null;
        bluetoothDevice = null;
        bluetoothState = State.UNINITIALIZED;
        if (DEBUG) {
            Log.d(LOG_TAG, "stop done: BT state=" + bluetoothState);
        }
    }

    /**
     * Starts Bluetooth SCO connection with remote device.
     * Note that the phone application always has the priority on the usage of the SCO connection
     * for telephony. If this method is called while the phone is in call it will be ignored.
     * Similarly, if a call is received or sent while an application is using the SCO connection,
     * the connection will be lost for the application and NOT returned automatically when the call
     * ends. Also note that: up to and including API version JELLY_BEAN_MR1, this method initiates a
     * virtual voice call to the Bluetooth headset. After API version JELLY_BEAN_MR2 only a raw SCO
     * audio connection is established.
     * TODO(henrika): should we add support for virtual voice call to BT headset also for JBMR2 and
     * higher. It might be required to initiates a virtual voice call since many devices do not
     * accept SCO audio without a "call".
     */
    public boolean startBluetoothAudio() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startScoAudio");
        }
        ThreadUtils.checkIsOnMainThread();
        if (DEBUG) {
            Log.d(LOG_TAG, "startSco: BT state=" + bluetoothState + ", "
                    + "attempts: " + scoConnectionAttempts + ", "
                    + "SCO is on: " + isScoOn());
        }

        if (audioManager == null) {
            Log.e(LOG_TAG, "BT SCO connection fails - audioManager is null");
            return false;
        }
        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.e(LOG_TAG, "BT SCO connection fails - no more attempts");
            return false;
        }
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Log.e(LOG_TAG, "BT SCO connection fails - no headset available");
            return false;
        }
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        if (DEBUG) {
            Log.d(LOG_TAG, "Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...");

        }
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be SCO_AUDIO_STATE_CONNECTED.
        bluetoothState = State.SCO_CONNECTING;
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        scoConnectionAttempts++;
        startTimer();
        if (DEBUG) {
            Log.d(LOG_TAG, "startScoAudio done: BT state=" + bluetoothState + ", "
                    + "SCO is on: " + isScoOn());
        }
        return true;
    }

    /**
     * Stops Bluetooth SCO connection with remote device.
     */
    public void stopBluetoothAudio() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopScoAudio: BT state=" + bluetoothState + ", "
                    + "SCO is on: " + isScoOn());
        }

        ThreadUtils.checkIsOnMainThread();
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) {
            return;
        }
        if (audioManager == null) {
            return;
        }
        cancelTimer();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);
        bluetoothState = State.SCO_DISCONNECTING;
        if (DEBUG) {
            Log.d(LOG_TAG, "stopScoAudio done: BT state=" + bluetoothState + ", "
                    + "SCO is on: " + isScoOn());
        }
    }

    /**
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset
     * Service via IPC) to update the list of connected devices for the HEADSET
     * profile. The internal state will change to HEADSET_UNAVAILABLE or to
     * HEADSET_AVAILABLE and |bluetoothDevice| will be mapped to the connected
     * device if available.
     */
    public void updateDevice() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDevice");
        }
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }
        try {
            // Get connected devices for the headset profile. Returns the set of
            // devices which are in state STATE_CONNECTED. The BluetoothDevice class
            // is just a thin wrapper for a Bluetooth hardware address.
            List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
            if (devices.isEmpty()) {
                bluetoothDevice = null;
                bluetoothState = State.HEADSET_UNAVAILABLE;
                if (DEBUG) {
                    Log.d(LOG_TAG, "No connected bluetooth headset");
                }
            } else {
                // Always use first device in list. Android only supports one device.
                bluetoothDevice = devices.get(0);
                bluetoothState = State.HEADSET_AVAILABLE;
                if (DEBUG) {
                    Log.d(LOG_TAG, "Connected bluetooth headset: "
                            + "name=" + bluetoothDevice.getName() + ", "
                            + "state=" + stateToString(bluetoothHeadset.getConnectionState(bluetoothDevice))
                            + ", SCO audio=" + bluetoothHeadset.isAudioConnected(bluetoothDevice));
                }
            }
        } catch (SecurityException exception) {
            bluetoothDevice = null;
            bluetoothState = State.HEADSET_UNAVAILABLE;
        }
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDevice done: BT state=" + bluetoothState);
        }
    }

    /**
     * Logs the state of the local Bluetooth adapter.
     */
    @SuppressLint("HardwareIds")
    private void logBluetoothAdapterInfo(BluetoothAdapter localAdapter) {
        if (DEBUG) {
            try {
                // Log the set of BluetoothDevice objects that are bonded (paired) to the local adapter.
                Set<BluetoothDevice> pairedDevices = localAdapter.getBondedDevices();
                if (!pairedDevices.isEmpty()) {
                    Log.d(LOG_TAG, "paired devices:");
                    for (BluetoothDevice device : pairedDevices) {
                        Log.d(LOG_TAG, " name=" + device.getName() + ", address=" + device.getAddress());
                    }
                }
            } catch (SecurityException exception) {
                Log.d(LOG_TAG, "Security exception", exception);
            }
        }
    }

    /**
     * Ensures that the audio manager updates its list of available audio devices.
     */
    private void updateAudioDeviceState() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateAudioDeviceState");
        }

        ThreadUtils.checkIsOnMainThread();
        twinmeAudioManager.updateAudioDeviceState();
    }

    /**
     * Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds.
     */
    private void startTimer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startTimer");
        }

        ThreadUtils.checkIsOnMainThread();
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS);
    }

    /**
     * Cancels any outstanding timer tasks.
     */
    private void cancelTimer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "cancelTimer");
        }

        ThreadUtils.checkIsOnMainThread();
        handler.removeCallbacks(bluetoothTimeoutRunnable);
    }

    /**
     * Called when start of the BT SCO channel takes too long time. Usually
     * happens when the BT device has been turned on during an ongoing call.
     */
    private void bluetoothTimeout() {
        if (DEBUG) {
            Log.d(LOG_TAG, "bluetoothTimeout");
        }

        ThreadUtils.checkIsOnMainThread();
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }
        if (DEBUG) {
            Log.d(LOG_TAG, "bluetoothTimeout: BT state=" + bluetoothState + ", "
                    + "attempts: " + scoConnectionAttempts + ", "
                    + "SCO is on: " + isScoOn());
        }
        if (bluetoothState != State.SCO_CONNECTING) {
            return;
        }
        // Bluetooth SCO should be connecting; check the latest result.
        boolean scoConnected = false;
        try {
            List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
            if (devices.size() > 0) {
                bluetoothDevice = devices.get(0);
                if (bluetoothDevice != null) {
                    if (bluetoothHeadset.isAudioConnected(bluetoothDevice)) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "SCO connected with " + bluetoothDevice.getName());
                        }
                        scoConnected = true;
                    } else {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "SCO is not connected with " + bluetoothDevice.getName());
                        }
                    }
                }
            }
        } catch (SecurityException exception) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Security exception", exception);
            }
        }
        if (scoConnected) {
            // We thought BT had timed out, but it's actually on; updating state.
            bluetoothState = State.SCO_CONNECTED;
            scoConnectionAttempts = 0;
        } else {
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Log.w(LOG_TAG, "BT failed to connect after timeout");
            stopBluetoothAudio();
        }
        updateAudioDeviceState();
        if (DEBUG) {
            Log.d(LOG_TAG, "bluetoothTimeout done: BT state=" + bluetoothState);
        }
    }

    /**
     * Checks whether audio uses Bluetooth SCO.
     */
    private boolean isScoOn() {
        return audioManager != null && audioManager.isBluetoothScoOn();
    }

    /**
     * Converts BluetoothAdapter states into local string representations.
     */
    private String stateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothAdapter.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothAdapter.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothAdapter.STATE_DISCONNECTING:
                return "DISCONNECTING";
            case BluetoothAdapter.STATE_OFF:
                return "OFF";
            case BluetoothAdapter.STATE_ON:
                return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF:
                // Indicates the local Bluetooth adapter is turning off. Local clients should immediately
                // attempt graceful disconnection of any remote links.
                return "TURNING_OFF";
            case BluetoothAdapter.STATE_TURNING_ON:
                // Indicates the local Bluetooth adapter is turning on. However local clients should wait
                // for STATE_ON before attempting to use the adapter.
                return "TURNING_ON";
            default:
                return "INVALID";
        }
    }
}
