/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.audio;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.webrtc.audio.WebRtcAudioUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

@RequiresApi(api = Build.VERSION_CODES.S)
public class BluetoothLEAManager implements BluetoothManager {
    private static final String LOG_TAG = "BluetoothLEAManager";
    private static final boolean DEBUG = false;

    private static final int BLUETOOTH_CONNECT_TIMEOUT_MS = 30 * 1000;
    private static final int MAX_CONNECT_RETRIES = 2;

    @NonNull
    private final Context context;
    @NonNull
    private final AudioManager audioManager;
    @NonNull
    private final Handler handler;
    private final Runnable bluetoothTimeoutRunnable = this::bluetoothTimeout;

    @Nullable
    private TwinmeOnCommunicationDeviceChangedListener listener;
    @Nullable
    private TwinmeAudioDeviceCallback callback;

    @Nullable
    private AudioDeviceInfo selectedCommunicationDevice = null;
    @NonNull
    private State bluetoothState = State.UNINITIALIZED;
    private int connectRetries = 0;
    private boolean ringtonePlaying = false;
    @NonNull
    private final TwinmeAudioManager twinmeAudioManager;

    private class TwinmeOnCommunicationDeviceChangedListener implements AudioManager.OnCommunicationDeviceChangedListener {

        @Override
        public void onCommunicationDeviceChanged(@Nullable AudioDeviceInfo device) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onCommunicationDeviceChanged: " + (device != null ? device.getProductName() : "null"));
            }

            if (device == null) {
                if (DEBUG) {
                    if (selectedCommunicationDevice != null) {
                        Log.d(LOG_TAG, "changed device to null but selectedCommunicationDevice is " + selectedCommunicationDevice.getProductName());
                    }
                }
                updateDevice();
            } else {
                if (DEBUG) {
                    if (selectedCommunicationDevice == null) {
                        Log.d(LOG_TAG, "changed device to " + device.getProductName() + " but selectedCommunicationDevice is null");
                    } else if (selectedCommunicationDevice.getId() != device.getId()) {
                        Log.d(LOG_TAG, "changed device to " + device.getProductName() + " but selectedCommunicationDevice is " + selectedCommunicationDevice.getProductName());

                        // Media button hack: with some BT headsets, the first press of the "answer" button
                        // does not trigger a MediaButtonEvent. Instead the headset disconnects for a short time,
                        // resulting in onCommunicationDeviceChanged() being called with the default output.
                        // So if this happens while the ringtone is playing, we simulate a button press to accept the call.
                        if (ringtonePlaying) {
                            KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK);
                            context.getMainExecutor().execute(() -> twinmeAudioManager.sendButtonEvent(event, SHORT_AUDIO_DISCONNECT_TIME + 1000));
                        }
                    }
                }

                if (selectedCommunicationDevice != null && device.getId() == selectedCommunicationDevice.getId()) {
                    bluetoothState = State.SCO_CONNECTED;
                    handler.removeCallbacks(bluetoothTimeoutRunnable);
                }
            }
            context.getMainExecutor().execute(twinmeAudioManager::updateAudioDeviceState);
        }
    }

    private class TwinmeAudioDeviceCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (DEBUG) {
                logDevices("onAudioDevicesAdded: ", addedDevices);
            }

            handler.removeCallbacks(bluetoothTimeoutRunnable);

            AudioDeviceInfo currentCommunicationDevice = audioManager.getCommunicationDevice();

            if (selectedCommunicationDevice != null && currentCommunicationDevice != null && selectedCommunicationDevice.getId() == currentCommunicationDevice.getId()) {
                // We're already connected to the selected device, ignore the new devices
                return;
            }

            bluetoothState = getAvailableBluetoothHeadset() != null ? State.HEADSET_AVAILABLE : State.HEADSET_UNAVAILABLE;

            final AudioDeviceInfo current = selectedCommunicationDevice;
            if (current != null) {
                for (AudioDeviceInfo device : addedDevices) {
                    if (current.getId() == device.getId()) {
                        // The previously selected device was reconnected, switch to it.
                        setSelectedCommunicationDevice(device);
                    }
                }
            }

            context.getMainExecutor().execute(twinmeAudioManager::updateAudioDeviceState);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (DEBUG) {
                logDevices("onAudioDevicesRemoved: ", removedDevices);
            }

            final AudioDeviceInfo current = selectedCommunicationDevice;
            if (current == null) {
                return;
            }

            for (AudioDeviceInfo device : removedDevices) {
                if (device.getId() == current.getId()) {
                    setSelectedCommunicationDevice(null);
                    break;
                }
            }

            bluetoothState = getAvailableBluetoothHeadset() != null ? State.HEADSET_AVAILABLE : State.HEADSET_UNAVAILABLE;

            context.getMainExecutor().execute(twinmeAudioManager::updateAudioDeviceState);
        }

        private void logDevices(String prefix, AudioDeviceInfo[] devices) {
            if (devices.length == 0) {
                Log.d(LOG_TAG, prefix + " no devices");
                return;
            }

            StringBuilder deviceNames = new StringBuilder(prefix);
            for (AudioDeviceInfo device : devices) {
                deviceNames.append(device.getProductName());
                deviceNames.append(", ");
            }
            deviceNames.delete(deviceNames.length() - 2, deviceNames.length());

            Log.d(LOG_TAG, deviceNames.toString());
        }
    }

    public BluetoothLEAManager(@NonNull Context context, @NonNull TwinmeAudioManager twinmeAudioManager) {
        this.context = context;
        audioManager = context.getSystemService(AudioManager.class);
        HandlerThread handlerThread = new HandlerThread("BluetoothLEAManagerThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        this.twinmeAudioManager = twinmeAudioManager;
    }

    @Override
    public void start() {
        if (listener == null) {
            listener = new TwinmeOnCommunicationDeviceChangedListener();
        }
        audioManager.addOnCommunicationDeviceChangedListener(Executors.newSingleThreadExecutor(), listener);

        if (callback == null) {
            callback = new TwinmeAudioDeviceCallback();
        }
        audioManager.registerAudioDeviceCallback(callback, handler);

        updateDevice();
    }

    @Override
    public boolean startBluetoothAudio() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startScoAudio");
        }
        AudioDeviceInfo btDevice = getAvailableBluetoothHeadset();
        if (btDevice != null) {
            return setSelectedCommunicationDevice(btDevice);
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "No BT device found");
        }
        return false;
    }

    @Override
    public void stopBluetoothAudio() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopScoAudio");
        }
        setSelectedCommunicationDevice(null);
    }

    @Override
    public void stop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stop");
        }
        if (listener != null) {
            audioManager.removeOnCommunicationDeviceChangedListener(listener);
        }

        if (callback != null) {
            audioManager.unregisterAudioDeviceCallback(callback);
        }

        setSelectedCommunicationDevice(null);

        ((HandlerThread) handler.getLooper().getThread()).quitSafely();
    }

    @Override
    public State getState() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getState: " + bluetoothState);
        }
        return bluetoothState;
    }

    private static final Set<Integer> BT_TYPES = Set.of(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET);

    @Nullable
    @Override
    public String getDeviceName() {
        for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
            if (BT_TYPES.contains(device.getType())) {
                return device.getProductName().toString();
            }
        }

        // No BT device found, returning active device name (which will probably be the phone's model name).
        AudioDeviceInfo activeDevice = audioManager.getCommunicationDevice();
        return activeDevice == null ? null : activeDevice.getProductName().toString();
    }

    @Override
    public void updateDevice() {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDevice");
        }
        AudioDeviceInfo btDevice = getAvailableBluetoothHeadset();

        if (btDevice == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "headset unavailable");
            }
            bluetoothState = State.HEADSET_UNAVAILABLE;
            return;
        }

        AudioDeviceInfo activeDevice = audioManager.getCommunicationDevice();

        if (activeDevice != null && activeDevice.getId() == btDevice.getId()) {
            if (DEBUG) {
                Log.d(LOG_TAG, "headset active");
            }
            bluetoothState = State.SCO_CONNECTED;
        } else {
            if (DEBUG) {
                Log.d(LOG_TAG, "headset available");
            }
            bluetoothState = State.HEADSET_AVAILABLE;
        }
    }

    @Override
    public void setRingtonePlaying(boolean ringtonePlaying) {
        this.ringtonePlaying = ringtonePlaying;
    }

    private synchronized boolean setSelectedCommunicationDevice(@Nullable AudioDeviceInfo device) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentCommunicationDevice: " + (device != null ? device.getProductName() : "null"));
        }

        selectedCommunicationDevice = device;

        if (selectedCommunicationDevice == null) {
            audioManager.clearCommunicationDevice();
            updateDevice();
            return true;
        }

        boolean accepted = audioManager.setCommunicationDevice(selectedCommunicationDevice);

        if (!accepted) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Could not set communication device " + selectedCommunicationDevice.getProductName());
            }

            bluetoothState = State.ERROR;
        } else {
            // Not SCO_CONNECTING, because TwinmeAudioManager.updateAudioDeviceState() would activate the speaker.
            // Maybe we should completely rewrite TwinmeAudioManager for Android >= 12?
            bluetoothState = State.SCO_CONNECTED;
        }

        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_CONNECT_TIMEOUT_MS);

        return accepted;
    }

    @Nullable
    private AudioDeviceInfo getAvailableBluetoothHeadset() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAvailableBluetoothDevice");
        }

        List<AudioDeviceInfo> availableCommunicationDevices = audioManager.getAvailableCommunicationDevices();

        if (DEBUG) {
            Log.d(LOG_TAG, "Available devices:");
            for (AudioDeviceInfo audioDeviceInfo : availableCommunicationDevices) {
                Log.d(LOG_TAG, "\t"+audioDeviceInfo.getProductName() + " id="+audioDeviceInfo.getId()+" type="+ WebRtcAudioUtils.deviceTypeToString(audioDeviceInfo.getType()));
            }
        }


        AudioDeviceInfo scoDevice = null;

        for (AudioDeviceInfo audioDeviceInfo : availableCommunicationDevices) {
            // BLE is best, return the device directly.
            if (AudioDeviceInfo.TYPE_BLE_HEADSET == audioDeviceInfo.getType()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Found Bluetooth LE headset " + audioDeviceInfo.getProductName());
                }
                return audioDeviceInfo;
            }

            // Don't return the SCO device directly, it could actually be a BLE device exposing SCO for compatibility.
            if (AudioDeviceInfo.TYPE_BLUETOOTH_SCO == audioDeviceInfo.getType()) {
                scoDevice = audioDeviceInfo;
            }
        }

        if (scoDevice != null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Found Bluetooth SCO headset " + scoDevice.getProductName());
            }
            return scoDevice;
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "No BLE headset found");
        }
        return null;
    }

    private void bluetoothTimeout() {
        if (DEBUG) {
            Log.d(LOG_TAG, "bluetoothTimeout");
        }

        connectRetries++;

        audioManager.clearCommunicationDevice();

        if (selectedCommunicationDevice != null) {
            audioManager.setCommunicationDevice(selectedCommunicationDevice);
            if (connectRetries < MAX_CONNECT_RETRIES) {
                handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_CONNECT_TIMEOUT_MS);
            }
        }

        context.getMainExecutor().execute(twinmeAudioManager::updateAudioDeviceState);
    }
}
