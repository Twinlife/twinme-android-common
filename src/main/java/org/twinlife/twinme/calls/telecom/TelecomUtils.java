/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.telecom;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.telecom.CallAudioState;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import org.twinlife.twinme.FeatureUtils;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.audio.AudioDevice;
import org.twinlife.twinme.calls.CallAssertPoint;
import org.twinlife.twinme.calls.CallConnection;
import org.twinlife.twinme.calls.CallService;
import org.twinlife.twinme.calls.CallStatus;
import org.twinlife.twinme.configuration.AppFlavor;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@RequiresApi(api = Build.VERSION_CODES.P)
public class TelecomUtils {
    private static final String LOG_TAG = "TelecomUtils";
    private static final boolean DEBUG = false;

    @NonNull
    public static PhoneAccountHandle getPhoneAccountHandle(@NonNull Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPhoneAccountHandle: context=" + context);
        }

        String packageName = context.getPackageName();

        ComponentName componentName = new ComponentName(packageName, TelecomConnectionService.class.getName());
        return new PhoneAccountHandle(componentName, packageName, Process.myUserHandle());
    }

    public static void registerPhoneAccount(@NonNull Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "registerPhoneAccount: context=" + context);
        }

        if (!FeatureUtils.isTelecomSupported(context)) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Telecom not supported by this device, skipping registration");
            }
            return;
        }

        try {
            TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
            TwinmeApplicationImpl application = TwinmeApplicationImpl.getInstance(context);

            if (telecomManager == null || application == null) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Could not get TelecomManager: " + telecomManager + " or TwinmeApplicationImpl: " + application);
                }
                return;
            }

            PhoneAccount phoneAccount = PhoneAccount.builder(getPhoneAccountHandle(context), application.getApplicationName())
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build();

            telecomManager.registerPhoneAccount(phoneAccount);
        } catch (Exception e) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Error occurred while registering phone account", e);
            }
        }
    }

    public static boolean addIncomingTelecomCall(@NonNull Context context, @NonNull UUID peerConnectionId, @NonNull Originator originator, boolean video) {
        if (DEBUG) {
            Log.d(LOG_TAG, "registerIncomingTelecomCall: context=" + context + " peerConnectionId=" + peerConnectionId + " video=" + video);
        }

        TelecomManager telecomManager = context.getSystemService(TelecomManager.class);

        if (!FeatureUtils.isTelecomSupported(context) || telecomManager == null) {
            return false;
        }

        Bundle extras = new Bundle();
        extras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, video ? VideoProfile.STATE_BIDIRECTIONAL : VideoProfile.STATE_AUDIO_ONLY);

        Bundle incomingCallExtras = new Bundle();
        incomingCallExtras.putSerializable(CallService.PARAM_PEER_CONNECTION_ID, peerConnectionId);
        incomingCallExtras.putString(TelecomConnectionService.PARAM_CALLER_DISPLAY_NAME, originator.getName());
        incomingCallExtras.putBoolean(TelecomConnectionService.PARAM_DISCREET_CONTACT, originator.getCapabilities().hasDiscreet());

        extras.putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, incomingCallExtras);

        try {
            telecomManager.addNewIncomingCall(getPhoneAccountHandle(context), extras);
            return true;
        } catch (Exception e) {
            Log.e(LOG_TAG, "TelecomManager.addNewIncomingCall() failed", e);
            reportException(context, CallAssertPoint.ADD_NEW_INCOMING_CALL, e);
            return false;
        }
    }

    public static synchronized void addOutgoingTelecomCall(@NonNull Context context, @NonNull Originator originator, @NonNull CallStatus callStatus, @NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "addOutgoingTelecomCall: context=" + context + " originator=" + originator + " callStatus=" + callStatus + " callConnection=" + callConnection);
        }

        TelecomManager telecomManager = context.getSystemService(TelecomManager.class);

        if (FeatureUtils.isTelecomSupported(context) &&
                !callConnection.getCall().isOutgoingTelecomCallRegistered() &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED) {

            String shortcutId = originator instanceof GroupMember ? ((GroupMember) originator).getGroup().getShortcutId() : originator.getShortcutId();

            // Not sure how this info is used for self-managed calls.
            // If there's a way for users to interact with this URI, we should add support for it in MainActivity.parseIntent().
            Uri uri = Uri.fromParts(AppFlavor.SKRED ? "skred" : "twinme", shortcutId, null);

            Bundle extras = new Bundle();

            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getPhoneAccountHandle(context));
            extras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, callStatus.isVideo() ? VideoProfile.STATE_BIDIRECTIONAL : VideoProfile.STATE_AUDIO_ONLY);
            extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, callStatus.isVideo());

            Bundle customExtras = new Bundle();
            customExtras.putSerializable(CallService.PARAM_CALL_ID, callConnection.getCall().getId());
            customExtras.putString(TelecomConnectionService.PARAM_CALLER_DISPLAY_NAME, originator.getName());
            customExtras.putSerializable(CallService.PARAM_PEER_CONNECTION_ID, callConnection.getPeerConnectionId());
            customExtras.putSerializable(TelecomConnectionService.PARAM_DISCREET_CONTACT, originator.getCapabilities().hasDiscreet());

            extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, customExtras);

            try {
                telecomManager.placeCall(uri, extras);
                callConnection.getCall().setOutgoingTelecomCallRegistered(true);
            } catch (Exception e) {
                Log.e(LOG_TAG, "TelecomManager.placeCall() failed", e);
                callConnection.getCall().setTelecomFailed(true);
                reportException(context, CallAssertPoint.PLACE_CALL, e);
            }
        }
    }

    private static void reportException(@NonNull Context context, @NonNull CallAssertPoint callAssertPoint, @NonNull Exception exception) {
        if (DEBUG) {
            Log.d(LOG_TAG, "reportException: exception=" + exception);
        }

        TwinmeApplicationImpl twinmeApplication = TwinmeApplicationImpl.getInstance(context);

        if (twinmeApplication == null) {
            return;
        }

        TwinmeContext twinmeContext = twinmeApplication.getTwinmeContext();

        if (twinmeContext == null) {
            return;
        }

        twinmeContext.exception(callAssertPoint, exception, null);
    }

    //
    // Audio Routing
    //

    @NonNull
    public static Set<AudioDevice> getAvailableAudioDevices(@NonNull CallAudioState audioState) {
        Set<AudioDevice> availableDevices = new HashSet<>();

        int route = audioState.getSupportedRouteMask();

        if ((route & CallAudioState.ROUTE_EARPIECE) == CallAudioState.ROUTE_EARPIECE) {
            availableDevices.add(AudioDevice.EARPIECE);
        }
        if ((route & CallAudioState.ROUTE_BLUETOOTH) == CallAudioState.ROUTE_BLUETOOTH) {
            availableDevices.add(AudioDevice.BLUETOOTH);
        }
        if ((route & CallAudioState.ROUTE_WIRED_HEADSET) == CallAudioState.ROUTE_WIRED_HEADSET) {
            availableDevices.add(AudioDevice.WIRED_HEADSET);
        }
        if ((route & CallAudioState.ROUTE_SPEAKER) == CallAudioState.ROUTE_SPEAKER) {
            availableDevices.add(AudioDevice.SPEAKER_PHONE);
        }
        if ((route & CallAudioState.ROUTE_STREAMING) == CallAudioState.ROUTE_STREAMING) {
            // Chromecast etc...
            // NOOP for now
        }
        return availableDevices;
    }

    @NonNull
    public static AudioDevice getAudioDevice(@Nullable CallAudioState audioState) {
        if (audioState == null) {
            return AudioDevice.NONE;
        }

        switch (audioState.getRoute()) {
            case CallAudioState.ROUTE_BLUETOOTH:
                return AudioDevice.BLUETOOTH;
            case CallAudioState.ROUTE_EARPIECE:
                return AudioDevice.EARPIECE;
            case CallAudioState.ROUTE_SPEAKER:
                return AudioDevice.SPEAKER_PHONE;
            case CallAudioState.ROUTE_WIRED_HEADSET:
                return AudioDevice.WIRED_HEADSET;
        }
        return AudioDevice.NONE;
    }

    public static int getAudioRoute(@NonNull AudioDevice audioDevice) {
        switch (audioDevice) {
            case BLUETOOTH:
                return CallAudioState.ROUTE_BLUETOOTH;
            case EARPIECE:
                return CallAudioState.ROUTE_EARPIECE;
            case SPEAKER_PHONE:
                return CallAudioState.ROUTE_SPEAKER;
            case WIRED_HEADSET:
                return CallAudioState.ROUTE_WIRED_HEADSET;
            case NONE:
            default:
                return CallAudioState.ROUTE_EARPIECE;
        }
    }
}
