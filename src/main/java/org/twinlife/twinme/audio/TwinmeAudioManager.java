/*
 *  Copyright (c) 2019-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

//
// based from : examples/androidapp/src/org/appspot/apprtc/AppRTCAudioManager.java
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

import static android.content.Intent.EXTRA_KEY_EVENT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.android.R;
import org.twinlife.twinme.calls.CallService;
import org.twinlife.twinme.calls.CallStatus;
import org.twinlife.twinme.calls.RingtoneSoundType;
import org.twinlife.twinme.calls.CallAudioManager;
import org.webrtc.ThreadUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * TwinmeAudioManager manages all audio related parts of application
 */
public class TwinmeAudioManager implements CallAudioManager {
    private static final String LOG_TAG = "TwinmeAudioManager";
    private static final boolean DEBUG = false;

    private static final int MAX_VOLUME = 100;

    private static final Map<AudioDevice, Set<Integer>> AUDIO_DEVICE_TYPE_MAPPING = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
            Map.of(
                    AudioDevice.EARPIECE, Set.of(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
                    AudioDevice.SPEAKER_PHONE, Set.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                    AudioDevice.WIRED_HEADSET, Set.of(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
                    AudioDevice.BLUETOOTH, Set.of(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            ) : Collections.emptyMap();

    /**
     * AudioManager state.
     */
    public enum AudioManagerState {
        UNINITIALIZED,
        RUNNING,
    }

    @NonNull
    private final Context context;
    @NonNull
    private final TwinmeContext twinmeContext;
    @Nullable
    private final AudioManager audioManager;

    @Nullable
    private AudioListener audioManagerEvents;
    private AudioManagerState amState;
    private int savedAudioMode = AudioManager.MODE_INVALID;
    private boolean savedIsSpeakerPhoneOn;
    private boolean savedIsMicrophoneMute;
    private boolean hasWiredHeadset;

    // Contains the currently selected audio device.
    // This device is changed automatically using a certain scheme where e.g.
    // a wired headset "wins" over speaker phone. It is also possible for a
    // user to explicitly select a device (and overrid any predefined scheme).
    // See |userSelectedAudioDevice| for details.
    private AudioDevice selectedAudioDevice;

    // Contains the user-selected audio device which overrides the predefined
    // selection scheme.
    // TODO(henrika): always set to AudioDevice.NONE today. Add support for
    // explicit selection based on choice by userSelectedAudioDevice.
    private AudioDevice userSelectedAudioDevice;

    // Proximity sensor object. It measures the proximity of an object in cm
    // relative to the view screen of a device and can therefore be used to
    // assist device switching (close to ear <=> use headset earpiece if
    // available, far from ear <=> use speaker phone).
    @Nullable
    private ProximitySensor proximitySensor;

    // Handles all tasks related to Bluetooth headset devices.
    @NonNull
    private final BluetoothManager bluetoothManager;

    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private Set<AudioDevice> audioDevices = new HashSet<>();

    // Broadcast receiver for wired headset intent broadcasts.
    private final BroadcastReceiver wiredHeadsetReceiver;

    // Callback method for changes in audio focus.
    @Nullable
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    @Nullable
    private AudioFocusRequest audioFocusRequest;

    private long lastMediaButtonTime;

    @Nullable
    private Vibrator vibrator;
    @Nullable
    private MediaSession mediaSession;
    @Nullable
    private RingtoneSoundType currentSoundType;

    /**
     * This method is called when the proximity sensor reports a state change,
     * e.g. from "NEAR to FAR" or from "FAR to NEAR".
     */
    private void onProximitySensorChangedState() {
        // The proximity sensor should only be activated when there are exactly two
        // available audio devices.
        if (audioDevices.size() == 2 && audioDevices.contains(AudioDevice.EARPIECE)
                && audioDevices.contains(AudioDevice.SPEAKER_PHONE)) {
            if (proximitySensor != null && proximitySensor.sensorReportsNearState()) {
                // Sensor reports that a "handset is being held up to a person's ear",
                // or "something is covering the light sensor".
                setCommunicationDevice(AudioDevice.EARPIECE);
            } else {
                // Sensor reports that a "handset is removed from a person's ear", or
                // "the light sensor is no longer covered".
                setCommunicationDevice(AudioDevice.SPEAKER_PHONE);
            }
        }
    }

    /* Receiver which handles changes in wired headset availability. */
    private class WiredHeadsetReceiver extends BroadcastReceiver {
        private static final int STATE_UNPLUGGED = 0;
        private static final int STATE_PLUGGED = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(LOG_TAG, "WiredHeadsetReceiver.onReceive");
            }

            int state = intent.getIntExtra("state", STATE_UNPLUGGED);
            hasWiredHeadset = (state == STATE_PLUGGED);
            updateAudioDeviceState();
        }
    }

    private class PlayerListener implements Player.Listener {

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onPlaybackStateChanged: playbackState=" + playbackState);
            }

            if (playbackState == Player.STATE_READY && audioManagerEvents != null) {
                audioManagerEvents.onPlayerReady();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.e(LOG_TAG, "onPlayerError", error);

            if (currentSoundType == null) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Already tried to recover or player already stopped, aborting");
                }

                releasePlayer();
                return;
            }

            final RingtoneSoundType soundType = currentSoundType;

            currentSoundType = null;

            final int fallback;
            final boolean repeat;
            switch (soundType) {
                case RINGTONE_OUTGOING_CALL_CONNECTING:
                    repeat = true;
                    fallback = R.raw.connecting_ringtone;
                    break;

                case RINGTONE_OUTGOING_CALL_RINGING:
                    repeat = true;
                    fallback = R.raw.ringing_ringtone;
                    break;

                case RINGTONE_END:
                    repeat = false;
                    fallback = R.raw.call_end_ringtone;
                    break;

                case RINGTONE_INCOMING_AUDIO_CALL:
                    repeat = true;
                    fallback = R.raw.audio_call_ringtone;
                    break;

                case RINGTONE_INCOMING_VIDEO_CALL:
                    repeat = true;
                    fallback = R.raw.video_call_ringtone;
                    break;

                default:
                    repeat = false;
                    fallback = 0;
                    break;
            }

            playMedia(soundType, Uri.parse("android.resource://" + context.getPackageName() + "/" + fallback), repeat);
        }
    }

    /**
     * Construction.
     */
    @NonNull
    public static TwinmeAudioManager create(@NonNull Context context, @NonNull TwinmeContext twinmeContext) {

        return new TwinmeAudioManager(context, twinmeContext);
    }

    private TwinmeAudioManager(@NonNull Context context, @NonNull TwinmeContext twinmeContext) {

        ThreadUtils.checkIsOnMainThread();
        this.context = context;
        this.twinmeContext = twinmeContext;
        audioManager = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothManager = new BluetoothLEAManager(context, this);
        } else {
            bluetoothManager = LegacyBluetoothManager.create(context, this);
        }

        wiredHeadsetReceiver = new WiredHeadsetReceiver();
        amState = AudioManagerState.UNINITIALIZED;

        // Create and initialize the proximity sensor.
        // Tablet devices (e.g. Nexus 7) does not support proximity sensors.
        // Note that, the sensor will not be active until start() has been called.
        proximitySensor = ProximitySensor.create(context,
                // This method will be called each time a state change is detected.
                // Example: user holds his hand over the device (closer than ~5 cm),
                // or removes his hand from the device.
                this::onProximitySensorChangedState);

        if (DEBUG) {
            Log.d(LOG_TAG, "WiredHeadsetReceiver.onReceive");
        }
    }

    public void start(@NonNull AudioListener audioManagerEvents, @Nullable Class<? extends BroadcastReceiver> mediaButtonReceiverClass) {
        if (DEBUG) {
            Log.d(LOG_TAG, "start audioManagerEvents=" + audioManagerEvents);
        }

        ThreadUtils.checkIsOnMainThread();
        if (audioManager == null) {
            Log.e(LOG_TAG, "audioManager is null");
            return;
        }
        if (amState == AudioManagerState.RUNNING) {
            Log.e(LOG_TAG, "AudioManager is already active");
            return;
        }
        // TODO(henrika): perhaps call new method called preInitAudio() here if UNINITIALIZED.
        if (DEBUG) {
            Log.d(LOG_TAG, "AudioManager starts...");
        }

        this.audioManagerEvents = audioManagerEvents;
        amState = AudioManagerState.RUNNING;

        mediaSession = null;

        // Setup a MediaSession and MediaController to be able to listen to media buttons.
        if (mediaButtonReceiverClass != null) {

            MediaSession.Callback callback = new MediaSession.Callback() {
                @UnstableApi
                @Override
                public boolean onMediaButtonEvent(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controllerInfo, @NonNull Intent intent) {

                    final KeyEvent event = intent.getParcelableExtra(EXTRA_KEY_EVENT);
                    if (event != null) {
                        sendButtonEvent(event, 0);
                    }

                    return true;
                }
            };

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                    .setUsage(C.USAGE_NOTIFICATION_RINGTONE)
                    .build();

            ExoPlayer player = new ExoPlayer.Builder(context)
                    .setAudioAttributes(audioAttributes, false)
                    .build();

            player.addListener(new PlayerListener());

            mediaSession = new MediaSession.Builder(context, player)
                    .setCallback(callback)
                    .build();
        }

        if (isTelecomSupported()) {
            // Audio focus and routing is handled by Telecom, so we just need the MediaSession
            // to play ringtones.
            return;
        }

        // Store current audio state so we can restore it when stop() is called.
        savedAudioMode = audioManager.getMode();
        savedIsSpeakerPhoneOn = isSpeakerphoneOn();
        savedIsMicrophoneMute = audioManager.isMicrophoneMute();
        hasWiredHeadset = hasWiredHeadset();

        // Create an AudioManager.OnAudioFocusChangeListener instance.
        // Called on the listener to notify if the audio focus for this listener has been changed.
        // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
        // and whether that loss is transient, or whether the new focus holder will hold it for an
        // unknown amount of time.
        // TODO(henrika): possibly extend support of handling audio-focus changes. Only contains
        // logging for now.
        audioFocusChangeListener = focusChange -> {
            final String typeOfChange;
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    typeOfChange = "AUDIOFOCUS_GAIN";
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                    typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT";
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                    typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                    typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    typeOfChange = "AUDIOFOCUS_LOSS";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
                    break;
                default:
                    typeOfChange = "AUDIOFOCUS_INVALID";
                    break;
            }
            if (DEBUG) {
                Log.d(LOG_TAG, "onAudioFocusChange: " + typeOfChange);
            }
        };
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.media.AudioAttributes mPlaybackAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(mPlaybackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();

            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            // Request audio playout focus (without ducking) and install listener for changes in focus.
            result = audioManager.requestAudioFocus(audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Audio focus request granted for VOICE_CALL streams");
            }
        } else {
            Log.e(LOG_TAG, "Audio focus request failed");
        }

        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        setMode(AudioManager.MODE_IN_COMMUNICATION);

        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false);

        // Set initial device states.
        userSelectedAudioDevice = AudioDevice.NONE;
        selectedAudioDevice = AudioDevice.NONE;
        audioDevices.clear();

        // Initialize and start Bluetooth if a BT device is available or initiate
        // detection of new (enabled) BT devices.
        bluetoothManager.start();

        // Do initial selection of audio device. This setting can later be changed
        // either by adding/removing a BT or wired headset or by covering/uncovering
        // the proximity sensor.
        updateAudioDeviceState();

        // Register receiver for broadcast intents related to adding/removing a
        // wired headset.
        registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        if (DEBUG) {
            Log.d(LOG_TAG, "AudioManager started");
        }
    }

    public void stop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stop");
        }

        ThreadUtils.checkIsOnMainThread();

        releasePlayer();

        // Hack to detect whether we're using Telecom. We can't use isTelecomSupported() because
        // at this point CallService.mActiveCall is already null.
        // audioFocusChangeListener == null => we were using Telecom when start() was called.
        if (audioFocusChangeListener == null) {
            // Other resources are not initialized when using Telecom.
            return;
        }

        if (audioManager == null) {
            Log.e(LOG_TAG, "audioManager is null");
            return;
        }
        if (amState != AudioManagerState.RUNNING) {
            Log.e(LOG_TAG, "Trying to stop AudioManager in incorrect state: " + amState);
            return;
        }
        amState = AudioManagerState.UNINITIALIZED;

        unregisterReceiver(wiredHeadsetReceiver);

        bluetoothManager.stop();

        // Restore previously stored audio states.
        setSpeakerphoneOn(savedIsSpeakerPhoneOn);
        setMicrophoneMute(savedIsMicrophoneMute);
        setMode(savedAudioMode);

        // Abandon audio focus. Gives the previous focus owner, if any, focus.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        audioFocusChangeListener = null;

        if (DEBUG) {
            Log.d(LOG_TAG, "Abandoned audio focus for VOICE_CALL streams");
        }

        if (proximitySensor != null) {
            proximitySensor.stop();
            proximitySensor = null;
        }

        audioManagerEvents = null;

        if (DEBUG) {
            Log.d(LOG_TAG, "AudioManager stopped");
        }
    }

    public boolean isRunning() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isRunning");
        }

        return amState == AudioManagerState.RUNNING;
    }

    void sendButtonEvent(@NonNull KeyEvent buttonEvent, long holdDelay) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendButtonEvent buttonEvent=" + buttonEvent + " holdDelay=" + holdDelay);
        }

        if (audioManagerEvents != null) {
            final long now = System.currentTimeMillis();
            if (now - lastMediaButtonTime > holdDelay) {
                lastMediaButtonTime = holdDelay;
                audioManagerEvents.onMediaButton(buttonEvent);
            }
        }
    }


    /**
     * Changes selection of the currently active audio device.
     */
    public void selectAudioDevice(AudioDevice device) {
        if (DEBUG) {
            Log.d(LOG_TAG, "selectAudioDevice device=" + device);
        }

        ThreadUtils.checkIsOnMainThread();
        if (device != AudioDevice.NONE && !audioDevices.contains(device)) {
            Log.e(LOG_TAG, "Can not select " + device + " from available " + audioDevices);
        }
        userSelectedAudioDevice = device;
    }

    public void setMode(int mode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setMode= " + mode);
        }

        if (isTelecomSupported()) {
            // Mode is handled by Telecom
            return;
        }

        if (audioManager != null) {
            audioManager.setMode(mode);
        }
    }

    /**
     * Returns the currently selected audio device.
     */
    @NonNull
    public AudioDevice getSelectedAudioDevice() {
        if (selectedAudioDevice == AudioDevice.NONE && audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo device = audioManager.getCommunicationDevice();
                if (device != null) {
                    for (Map.Entry<AudioDevice, Set<Integer>> deviceMapping : AUDIO_DEVICE_TYPE_MAPPING.entrySet()) {
                        if (deviceMapping.getValue().contains(device.getType())) {
                            selectedAudioDevice = deviceMapping.getKey();
                            break;
                        }
                    }
                }
            } else {
                if (bluetoothManager.getState() == BluetoothManager.State.SCO_CONNECTED) {
                    selectedAudioDevice = AudioDevice.BLUETOOTH;
                } else {
                    selectedAudioDevice = audioManager.isSpeakerphoneOn() ? AudioDevice.SPEAKER_PHONE : AudioDevice.EARPIECE;
                }
            }
        }
        return selectedAudioDevice;
    }

    public boolean isHeadsetAvailable() {

        return audioDevices != null && (audioDevices.contains(AudioDevice.BLUETOOTH) || audioDevices.contains(AudioDevice.WIRED_HEADSET));
    }

    @Nullable
    public String getBluetoothDeviceName() {
        return bluetoothManager.getDeviceName();
    }

    @NonNull
    public Set<AudioDevice> getAudioDevices() {
        return audioDevices;
    }

    /**
     * Helper method for receiver registration.
     */
    private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        // Register broadcast receiver and make it visible because we expect an external component to send messages.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * Helper method for unregistration of an existing receiver.
     */
    private void unregisterReceiver(BroadcastReceiver receiver) {
        context.unregisterReceiver(receiver);
    }

    /**
     * Sets the speaker phone mode.
     */
    public void setSpeakerphoneOn(boolean on) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setSpeakerphoneOn on=" + on);
        }
        if (audioManager != null) {
            boolean wasOn = isSpeakerphoneOn();
            if (wasOn == on) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setSpeakerphoneCommunicationDevice(on);
            } else {
                audioManager.setSpeakerphoneOn(on);
                updateAudioDeviceState();
            }
        }
    }

    public boolean isSpeakerphoneOn() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isSpeakerphoneOn");
        }

        if (audioManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo audioDeviceInfo = audioManager.getCommunicationDevice();
            return audioDeviceInfo != null && audioDeviceInfo.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } else {
            return audioManager.isSpeakerphoneOn();
        }
    }

    public void setCommunicationDevice(@NonNull AudioDevice device) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCommunicationDevice: device=" + device);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            internalSetCommunicationDevice(device);
        } else {
            internalSetCommunicationDeviceLegacy(device);
        }

        selectedAudioDevice = device;
        userSelectedAudioDevice = device;
    }

    private void internalSetCommunicationDeviceLegacy(@NonNull AudioDevice device) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCommunicationDeviceLegacy: device=" + device);
        }

        if (audioManager == null) {
            return;
        }

        switch (device) {
            case BLUETOOTH:
                setSpeakerphoneOn(false);
                bluetoothManager.startBluetoothAudio();
                break;
            case SPEAKER_PHONE:
                setSpeakerphoneOn(true);
                break;
            case EARPIECE:
                bluetoothManager.stopBluetoothAudio();
                setSpeakerphoneOn(false);
                break;
            case WIRED_HEADSET: // Couldn't find a way to force wired headsets on SDK < S, AudioManager.setWiredHeadsetOn() is deprecated and NOOP.
            default:
                Log.w(LOG_TAG, "setCommunicationDeviceLegacy can't handle device " + device);
                break;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void internalSetCommunicationDevice(@NonNull AudioDevice device) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCommunicationDevice: device=" + device);
        }

        if (audioManager == null) {
            return;
        }

        AudioDeviceInfo audioDeviceInfo = getAudioDeviceInfo(device);

        if (audioDeviceInfo != null) {
            audioManager.setCommunicationDevice(audioDeviceInfo);
        } else {
            Log.w(LOG_TAG, "No device with type " + device + " found");
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Nullable
    private AudioDeviceInfo getAudioDeviceInfo(AudioDevice audioDevice) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAudioDeviceInfo: audioDevice=" + audioDevice);
        }

        Set<Integer> targetDeviceTypes = AUDIO_DEVICE_TYPE_MAPPING.get(audioDevice);

        if (audioManager != null && targetDeviceTypes != null) {
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                if (targetDeviceTypes.contains(device.getType())) {
                    return device;
                }
            }
        }

        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void setSpeakerphoneCommunicationDevice(boolean on) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setSpeakerphoneCommunicationDevice on=" + on);
        }
        if (audioManager == null) {
            return;
        }

        if (on) {
            setCommunicationDevice(AudioDevice.SPEAKER_PHONE);
        } else {
            // Turn speakerphone OFF.
            audioManager.clearCommunicationDevice();
        }
    }

    /**
     * Sets the microphone mute state.
     */
    public void setMicrophoneMute(boolean on) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setMicrophoneMute: on=" + on);
        }
        if (audioManager != null) {
            boolean wasMuted = audioManager.isMicrophoneMute();
            if (wasMuted == on) {
                return;
            }
            audioManager.setMicrophoneMute(on);
        }
    }

    /**
     * Gets the current earpiece state.
     */
    private boolean hasEarpiece() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    /**
     * Checks whether a wired headset is connected or not.
     * This is not a valid indication that audio playback is actually over
     * the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached
     * wired headset.
     */
    private boolean hasWiredHeadset() {
        if (DEBUG) {
            Log.d(LOG_TAG, "hasWiredHeadset");
        }

        if (audioManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            //noinspection deprecation
            return audioManager.isWiredHeadsetOn();
        } else {
            final AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo device : devices) {
                final int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "hasWiredHeadset: found wired headset");
                    }
                    return true;
                } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "hasWiredHeadset: found USB audio device");
                    }
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Updates list of possible audio devices and make new device selection.
     * TODO(henrika): add unit test to verify all state transitions.
     */
    public void updateAudioDeviceState() {

        ThreadUtils.checkIsOnMainThread();

        BluetoothManager.State state = bluetoothManager.getState();

        if (DEBUG) {
            Log.d(LOG_TAG, "--- updateAudioDeviceState: "
                    + "wired headset=" + hasWiredHeadset + ", "
                    + "BT state=" + state);
            Log.d(LOG_TAG, "Device status: "
                    + "available=" + audioDevices + ", "
                    + "selected=" + selectedAudioDevice + ", "
                    + "user selected=" + userSelectedAudioDevice);
        }

        // Check if any Bluetooth headset is connected. The internal BT state will
        // change accordingly.
        // TODO(henrika): perhaps wrap required state into BT manager.
        if (state == BluetoothManager.State.HEADSET_AVAILABLE
                || state == BluetoothManager.State.HEADSET_UNAVAILABLE
                || state == BluetoothManager.State.SCO_DISCONNECTING) {
            bluetoothManager.updateDevice();
            state = bluetoothManager.getState();
        }

        // Update the set of available audio devices.
        Set<AudioDevice> newAudioDevices = new HashSet<>();

        if (state == BluetoothManager.State.SCO_CONNECTED
                || state == BluetoothManager.State.SCO_CONNECTING
                || state == BluetoothManager.State.HEADSET_AVAILABLE) {
            newAudioDevices.add(AudioDevice.BLUETOOTH);
        }

        if (hasWiredHeadset) {
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET);
        } else {
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE);
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE);
            }
        }
        // Store state which is set to true if the device list has changed.
        boolean audioDeviceSetUpdated = !audioDevices.equals(newAudioDevices);
        // Update the existing audio device set.
        audioDevices = newAudioDevices;
        // Correct user selected audio devices if needed.
        if (state == BluetoothManager.State.HEADSET_UNAVAILABLE
                && userSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            // If BT is not available, it can't be the user selection.
            userSelectedAudioDevice = AudioDevice.NONE;
        }
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET;
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            // If user selected wired headset, but then unplugged wired headset then make
            // speaker phone as user selected device.
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE;
        }

        // Need to start Bluetooth if it is available and user either selected it explicitly or
        // user did not select any output device.
        boolean needBluetoothAudioStart =
                state == BluetoothManager.State.HEADSET_AVAILABLE
                        && (userSelectedAudioDevice == AudioDevice.NONE
                        || userSelectedAudioDevice == AudioDevice.BLUETOOTH);

        // Need to stop Bluetooth audio if user selected different device and
        // Bluetooth SCO connection is established or in the process.
        boolean needBluetoothAudioStop =
                (state == BluetoothManager.State.SCO_CONNECTED
                        || bluetoothManager.getState() == BluetoothManager.State.SCO_CONNECTING)
                        && (userSelectedAudioDevice != AudioDevice.NONE
                        && userSelectedAudioDevice != AudioDevice.BLUETOOTH);

        if (state == BluetoothManager.State.HEADSET_AVAILABLE
                || state == BluetoothManager.State.SCO_CONNECTING
                || state == BluetoothManager.State.SCO_CONNECTED) {

            if (DEBUG) {
                Log.d(LOG_TAG, "Need BT audio: start=" + needBluetoothAudioStart + ", "
                        + "stop=" + needBluetoothAudioStop + ", "
                        + "BT state=" + state);
            }
        }

        // Start or stop Bluetooth SCO connection given states set earlier.
        if (needBluetoothAudioStop && isLegacyBTManager()) {
            bluetoothManager.stopBluetoothAudio();
            bluetoothManager.updateDevice();
        }

        if (needBluetoothAudioStart && !needBluetoothAudioStop) {
            // Attempt to start Bluetooth SCO audio (takes a few second to start).
            if (!bluetoothManager.startBluetoothAudio()) {
                // Remove BLUETOOTH from list of available devices since SCO failed.
                audioDevices.remove(AudioDevice.BLUETOOTH);
                audioDeviceSetUpdated = true;
            }
        }

        // Update selected audio device.
        final AudioDevice newAudioDevice;

        state = bluetoothManager.getState();
        if (state == BluetoothManager.State.SCO_CONNECTED && isLegacyBTManager()) {
            // If a Bluetooth is (being) connected, then it should be used as output audio
            // device. Note that it is not sufficient that a headset is available;
            // an active SCO channel must also be up and running.
            newAudioDevice = AudioDevice.BLUETOOTH;
        } else if (hasWiredHeadset) {
            // If a wired headset is connected, but Bluetooth is not, then wired headset is used as
            // audio device.
            newAudioDevice = AudioDevice.WIRED_HEADSET;
        } else {
            // No wired headset and no Bluetooth, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            // |defaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or AudioDevice.EARPIECE
            // depending on the user's selection.
            newAudioDevice = userSelectedAudioDevice;
        }
        // Switch to new device but only if there has been any changes.
        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            // Do the required device switch.
            setCommunicationDevice(newAudioDevice);
            if (DEBUG) {
                Log.d(LOG_TAG, "New device status: "
                        + "available=" + audioDevices + ", "
                        + "selected=" + newAudioDevice);
            }
        }

        if (audioManagerEvents != null) {
            // Notify a listening client that audio device has been changed.
            audioManagerEvents.onAudioDeviceChanged(userSelectedAudioDevice, audioDevices);
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "--- updateAudioDeviceState done");
        }
    }

    private boolean isLegacyBTManager() {
        return bluetoothManager instanceof LegacyBluetoothManager;
    }

    public int getSavedAudioMode() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSavedAudioMode");
        }

        return savedAudioMode;
    }

    public void startRingtone(@NonNull RingtoneSoundType type, @Nullable CallStatus mode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startRingtone type=" + type + " mode=" + mode);
        }

        // Tell BluetoothLEAManager that we're playing a ringtone
        // for the media button hack (see TwinmeOnCommunicationDeviceChangedListener.onCommunicationDeviceChanged)
        bluetoothManager.setRingtonePlaying(true);

        if ((type == RingtoneSoundType.RINGTONE_INCOMING_AUDIO_CALL || type == RingtoneSoundType.RINGTONE_INCOMING_VIDEO_CALL) && twinmeContext.getNotificationCenter().isDoNotDisturb()) {
            if (DEBUG) {
                Log.d(LOG_TAG, "DND is on, aborting");
            }
            return;
        }

        if (mediaSession == null) {
            Log.e(LOG_TAG, "TwinmeAudioManager.start() must be called before startRingtone()");
            return;
        }

        if (audioManager == null) {
            Log.e(LOG_TAG, "audioManager not defined, this should not happen");
            return;
        }

        int ringerMode = audioManager.getRingerMode();

        final boolean repeat;
        final Uri ringtoneUri;
        switch (type) {
            case RINGTONE_OUTGOING_CALL_CONNECTING:
                repeat = true;
                ringtoneUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.connecting_ringtone);
                break;

            case RINGTONE_OUTGOING_CALL_RINGING:
                repeat = true;
                ringtoneUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.ringing_ringtone);
                break;

            case RINGTONE_END:
                repeat = false;
                ringtoneUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.call_end_ringtone);
                break;

            case RINGTONE_INCOMING_AUDIO_CALL:
                repeat = true;
                ringtoneUri = twinmeContext.getNotificationCenter().getRingtone(false);
                break;

            case RINGTONE_INCOMING_VIDEO_CALL:
                repeat = true;
                ringtoneUri = twinmeContext.getNotificationCenter().getRingtone(true);
                break;

            default:
                repeat = false;
                ringtoneUri = null;
                break;
        }

        if (ringtoneUri == null) {

            return;
        }

        currentSoundType = type;
        boolean vibrating = false;
        boolean playRingtone = false;

        if (mode != null) {
            switch (mode) {
                case IN_CALL:
                case IN_VIDEO_BELL:
                case OUTGOING_CALL:
                case OUTGOING_VIDEO_CALL:
                case OUTGOING_VIDEO_BELL:
                case ACCEPTED_OUTGOING_CALL:
                case TERMINATED:
                case FALLBACK:
                    setMode(AudioManager.MODE_IN_COMMUNICATION);
                    playRingtone = true;
                    break;

                case INCOMING_CALL:
                case INCOMING_VIDEO_CALL:
                case INCOMING_VIDEO_BELL:
                case ACCEPTED_INCOMING_CALL:
                    setMode(AudioManager.MODE_RINGTONE);
                    playRingtone = ringerMode == AudioManager.RINGER_MODE_NORMAL;
                    break;
            }

            if (mode == CallStatus.INCOMING_VIDEO_CALL) {
                vibrating = twinmeContext.getNotificationCenter().videoVibrate();
            } else if (mode == CallStatus.INCOMING_CALL) {
                vibrating = twinmeContext.getNotificationCenter().audioVibrate();
            }

            vibrating = vibrating && ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else if (type == RingtoneSoundType.RINGTONE_END) {
            // mode == null ==> CallService.getActiveCall() == null
            setMode(AudioManager.MODE_IN_COMMUNICATION);
            playRingtone = true;
        }

        if (playRingtone) {
            playMedia(type, ringtoneUri, repeat);
        }

        if (vibrating) {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

            long[] pattern = {0L, 500L, 600L, 1100L, 1200L, 1700L};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.media.AudioAttributes audioAttributesRingtone = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build();
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, 1);
                twinmeContext.execute(() -> vibrator.vibrate(effect, audioAttributesRingtone));
            } else {
                twinmeContext.execute(() -> vibrator.vibrate(pattern, 1));
            }
            this.vibrator = vibrator;
        }
    }

    private void playMedia(@NonNull RingtoneSoundType type, Uri ringtoneUri, boolean repeat) {
        if (DEBUG) {
            Log.d(LOG_TAG, "playMedia: type=" + type + " ringtoneUri=" + ringtoneUri + " repeat=" + repeat);
        }

        if (mediaSession == null) {
            Log.d(LOG_TAG, "start() must be called before using the player.");
            return;
        }

        Player player = mediaSession.getPlayer();

        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION);
        switch (type) {
            case RINGTONE_OUTGOING_CALL_CONNECTING:
            case RINGTONE_OUTGOING_CALL_RINGING:
            case RINGTONE_END:
                builder.setUsage(C.USAGE_VOICE_COMMUNICATION);
                break;
            case RINGTONE_INCOMING_AUDIO_CALL:
            case RINGTONE_INCOMING_VIDEO_CALL:
                // On some devices (Samsung, OPPO) using USAGE_NOTIFICATION_RINGTONE makes the ringtone play through the internal earpiece.
                builder.setUsage(C.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
                break;
            default:
                builder.setUsage(C.USAGE_NOTIFICATION);
        }

        player.setAudioAttributes(builder.build(), false);
        player.setMediaItem(MediaItem.fromUri(ringtoneUri));
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.prepare();
        player.play();
    }

    public void stopRingtone() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopRingtone");
        }

        // Tell BluetoothLEAManager that we're not playing a ringtone anymore
        // for the media button hack (see TwinmeOnCommunicationDeviceChangedListener.onCommunicationDeviceChanged)
        bluetoothManager.setRingtonePlaying(false);

        final Player player;
        final Vibrator vibrator;
        synchronized (this) {
            if (mediaSession == null) {
                return;
            }

            player = mediaSession.getPlayer();
            vibrator = this.vibrator;
            currentSoundType = null;
            this.vibrator = null;
        }

        player.stop();
        player.clearMediaItems();

        if (vibrator != null) {
            vibrator.cancel();
        }

        setMode(AudioManager.MODE_IN_COMMUNICATION);
    }

    public void setSpeaker(boolean audioSpeaker) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setSpeaker: speaker=" + audioSpeaker);
        }

        // Switch to the speaker or select the default device when speaker is off.
        if (audioSpeaker != isSpeakerphoneOn()) {
            selectAudioDevice(audioSpeaker ? AudioDevice.SPEAKER_PHONE : AudioDevice.NONE);
            setSpeakerphoneOn(audioSpeaker);
            updateAudioDeviceState();
        }
    }

    /**
     * Set up the initial communication device according to available devices and type of call.
     * <p>
     * Audio call: use default device (external headset if available, earpiece otherwise)
     * Video call: use external headset if available, speaker otherwise.
     *
     * @param videoCall true if the call is initially a video call.
     */
    public void initDevice(boolean videoCall) {
        if (DEBUG) {
            Log.d(LOG_TAG, "initOutput: videoCall=" + videoCall);
        }

        if (audioManager == null) {
            Log.w(LOG_TAG, "audioManager is not initialized, can't init output.");
            return;
        }

        if (isTelecomSupported()) {
            // Device is handled by Telecom
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!videoCall) {
                // Nothing to do for audio calls: AudioManager has already selected the headset if available,
                // or the earpiece otherwise.
                return;
            }

            boolean headsetAvailable = false;
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                if (AUDIO_DEVICE_TYPE_MAPPING.get(AudioDevice.BLUETOOTH).contains(device.getType()) ||
                        AUDIO_DEVICE_TYPE_MAPPING.get(AudioDevice.WIRED_HEADSET).contains(device.getType())) {
                    headsetAvailable = true;
                    break;
                }
            }

            if (!headsetAvailable) {
                AudioDeviceInfo speaker = getAudioDeviceInfo(AudioDevice.SPEAKER_PHONE);
                if (speaker == null) {
                    Log.w(LOG_TAG, "Can't find speaker device");
                    return;
                }
                audioManager.setCommunicationDevice(speaker);
            }
        } else {
            if (videoCall &&
                    Set.of(BluetoothManager.State.HEADSET_UNAVAILABLE, BluetoothManager.State.ERROR).contains(bluetoothManager.getState())) {
                //video call and BT headset not available, default to speaker.
                userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE;
            } else {
                // Otherwise let updateAudioDeviceState() select the appropriate device.
                userSelectedAudioDevice = AudioDevice.NONE;
            }
            updateAudioDeviceState();
        }
    }

    /**
     * Ensure the volume is in the appropriate boundaries, and apply it to the player.
     * <p>
     * Note: see <a href="https://stackoverflow.com/questions/5215459/android-mediaplayer-setvolume-function">this SO thread</a>
     * for a discussion on how to scale the volume in a way which feels natural to human ears (i.e. going from 75% to 100% feels 25% louder).
     * For now we use a simple linear scale, which does the job for ringtone volume, but may not be appropriate for controlling music or video volume.
     * </p>
     *
     * @param player
     * @param volume min value is 0 (no sound), max value is 100 (full volume). Values less than 0 will be converted to 0, values greater than 100 will be converted to 100.
     */
    public void setMediaPlayerVolume(@NonNull MediaPlayer player, int volume) {
        volume = Math.min(volume, MAX_VOLUME);
        volume = Math.max(volume, 0);

        final float v = (float) volume / MAX_VOLUME;
        player.setVolume(v, v);
    }

    /**
     * Release the media player.  Be careful that it can be called from two different threads at the same time.
     */
    private synchronized void releasePlayer() {

        final MediaSession session = mediaSession;
        mediaSession = null;
        if (session != null) {
            session.getPlayer().stop();
            session.getPlayer().release();
            session.release();
        }
    }

    private boolean isTelecomSupported() {
        return CallService.getState() != null && CallService.getState().isTelecomSupported();
    }
}
