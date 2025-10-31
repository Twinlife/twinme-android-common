/*
 *  Copyright (c) 2024-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.telecom;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.twinlife.twinlife.TerminateReason;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.audio.AudioDevice;
import org.twinlife.twinme.calls.CallAudioManager;
import org.twinlife.twinme.calls.CallService;
import org.twinlife.twinme.calls.CallStatus;
import org.twinlife.twinme.ui.Intents;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@RequiresApi(api = Build.VERSION_CODES.P)
public class TelecomConnectionService extends ConnectionService implements CallAudioManager, TelecomConnection.Listener {
    private static final String LOG_TAG = "TelecomConnectionSvc";
    private static final boolean DEBUG = false;

    static final String PARAM_CALLER_DISPLAY_NAME = "callerDisplayName";
    static final String PARAM_DISCREET_CONTACT = "discreetContact";

    @Nullable
    private static TelecomConnectionService INSTANCE = null;

    @Nullable
    public static TelecomConnectionService getInstance() {
        return INSTANCE;
    }

    private class CallServiceReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onReceive: context=" + context + " intent=" + intent);
            }

            if (intent == null || intent.getExtras() == null) {

                return;
            }

            final String event = intent.getExtras().getString(CallService.CALL_SERVICE_EVENT);
            if (event == null) {
                return;
            }

            // Catch exception in case an external app succeeds in sending a message.
            try {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Received event=" + event);
                }
                switch (event) {
                    case CallService.MESSAGE_CONNECTION_STATE:
                        // Set the connection to active if the call is accepted.
                        TelecomConnectionService.this.onMessageChangeConnectionState(intent);
                        break;
                    case CallService.MESSAGE_TERMINATE_CALL:
                        TelecomConnectionService.this.onMessageTerminateCall(intent);
                        break;
                    case CallService.MESSAGE_CAMERA_MUTE_UPDATE:
                        TelecomConnectionService.this.onMessageCameraMuteUpdate(intent);
                        break;
                    case CallService.MESSAGE_CALL_ON_HOLD:
                        TelecomConnectionService.this.onMessageCallOnHold(intent);
                        break;
                    case CallService.MESSAGE_CALL_RESUMED:
                        TelecomConnectionService.this.onMessageCallResumed(intent);
                        break;
                    default:
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Call event " + event + " not handled");
                        }
                        break;
                }
            } catch (Exception exception) {
                if (Logger.WARN) {
                    Log.w(LOG_TAG, "Invalid message", exception);
                }
            }
        }
    }

    /**
     * Connections not associated with a call yet.
     * Connections are mapped to a callId when the call is accepted.
     */
    @NonNull
    private final List<TelecomConnection> pendingConnections = new ArrayList<>();

    /**
     * Accepted connections, mapped to their callId.
     * <p>
     * NB: for now we only register one connection by call.
     */
    @NonNull
    private final Map<UUID, List<TelecomConnection>> connectionsByCallId = new HashMap<>();

    @Nullable
    private CallServiceReceiver mCallReceiver;

    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreate");
        }

        super.onCreate();

        INSTANCE = this;

        // Listen to the CallService messages.
        IntentFilter filter = new IntentFilter(Intents.INTENT_CALL_SERVICE_MESSAGE);
        mCallReceiver = new CallServiceReceiver();

        // Register and avoid exporting the call receiver.
        ContextCompat.registerReceiver(getBaseContext(), mCallReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDestroy");
        }

        if (mCallReceiver != null) {
            unregisterReceiver(mCallReceiver);
            mCallReceiver = null;
        }

        INSTANCE = null;

        super.onDestroy();
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateOutgoingConnection: connectionManagerPhoneAccount=" + connectionManagerPhoneAccount + " request=" + request);
        }

        return buildTelecomConnection(request, request.getExtras());
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateOutgoingConnectionFailed: connectionManagerPhoneAccount=" + connectionManagerPhoneAccount + " request=" + request);
        }
        // From the doc: Your app may not be able to place a call if there is an ongoing emergency call,
        // or if there is an ongoing call in another app which cannot be put on hold before placing your call.

        Bundle extras = request.getExtras().getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
        if (extras == null) {
            Log.e(LOG_TAG, "Bundle doesn't contain " + TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
            return;
        }

        handleFailedConnection(extras);
    }

    @Nullable
    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateIncomingConnection: connectionManagerPhoneAccount=" + connectionManagerPhoneAccount + " request=" + request);
        }

        Bundle extras = request.getExtras().getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);

        if (extras == null) {
            Log.e(LOG_TAG, "Extras don't contain " + TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
            return null;
        }

        Connection connection = buildTelecomConnection(request, extras);

        if (connection != null) {
            CallService.startService(this);
        }

        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateIncomingConnectionFailed: connectionManagerPhoneAccount=" + connectionManagerPhoneAccount + " request=" + request);
        }

        Bundle extras = request.getExtras().getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
        if (extras == null) {
            Log.e(LOG_TAG, "Bundle doesn't contain " + TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
            return;
        }

        handleFailedConnection(extras);
    }

    @Nullable
    private TelecomConnection buildTelecomConnection(@NonNull ConnectionRequest request, @NonNull Bundle extras) {
        if (DEBUG) {
            Log.d(LOG_TAG, "buildTelecomConnection: request=" + request + " extras=" + extras);
        }

        UUID peerConnectionId = (UUID) extras.getSerializable(CallService.PARAM_PEER_CONNECTION_ID);
        String callerDisplayName = extras.getString(PARAM_CALLER_DISPLAY_NAME, null); //TODO TEL: default value?
        boolean discreet = extras.getBoolean(PARAM_DISCREET_CONTACT, false);
        int videoState = VideoProfile.STATE_AUDIO_ONLY;

        if (extras.getInt(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, -1) != -1) {
            videoState = extras.getInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE);
        } else if (extras.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, -1) != -1) {
            videoState = extras.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE);
        }

        if (peerConnectionId == null) {
            Log.e(LOG_TAG, "No peerConnectionId in Telecom request, aborting");
            return null;
        }

        TelecomConnection connection = new TelecomConnection(peerConnectionId, this);

        connection.setConnectionCapabilities(Connection.CAPABILITY_HOLD | Connection.CAPABILITY_SUPPORT_HOLD | Connection.CAPABILITY_MUTE | Connection.CAPABILITY_CAN_PAUSE_VIDEO);
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);

        connection.setCallerDisplayName(callerDisplayName, discreet ? TelecomManager.PRESENTATION_UNKNOWN : TelecomManager.PRESENTATION_ALLOWED);

        connection.setVideoState(videoState);
        connection.setAudioModeIsVoip(true);
        connection.setExtras(request.getExtras());
        connection.setInitializing();

        UUID callId = (UUID) request.getExtras().getSerializable(CallService.PARAM_CALL_ID);
        if (callId != null) {
            // Outgoing call: we already have a call ID.
            connectionsByCallId.computeIfAbsent(callId, key -> new ArrayList<>()).add(connection);
        } else {
            // Incoming call: we don't have a call ID yet => pending until MESSAGE_CONNECTION_STATE.
            pendingConnections.add(connection);
        }

        return connection;
    }

    private void handleFailedConnection(@NonNull Bundle extras) {
        if (DEBUG) {
            Log.d(LOG_TAG, "handleFailedConnection: extras=" + extras);
        }

        Bundle callServiceExtras = new Bundle();
        callServiceExtras.putSerializable(CallService.PARAM_TERMINATE_REASON, TerminateReason.BUSY);
        callServiceExtras.putSerializable(CallService.PARAM_PEER_CONNECTION_ID, extras.getSerializable(CallService.PARAM_PEER_CONNECTION_ID));

        sendCallServiceAction(CallService.ACTION_TERMINATE_CALL, callServiceExtras);
    }

    //
    // CallService intents handling
    //

    private void onMessageChangeConnectionState(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMessageChangeConnectionState: intent=" + intent);
        }

        CallStatus callStatus = (CallStatus) intent.getSerializableExtra(CallService.CALL_SERVICE_STATE);

        if (!CallStatus.isAccepted(callStatus) && !CallStatus.isActive(callStatus)) {
            return;
        }

        UUID callId = (UUID) intent.getSerializableExtra(CallService.CALL_ID);

        // Look for an incoming connection (not associated with a call yet).
        TelecomConnection connection = getPendingConnection(intent);

        if (connection == null && callId != null) {
            // Look for an outgoing connection.
            List<TelecomConnection> connections = connectionsByCallId.get(callId);
            if (connections != null && !connections.isEmpty()) {
                connection = connections.get(0);

                if (!Arrays.asList(Connection.STATE_INITIALIZING, Connection.STATE_RINGING, Connection.STATE_DIALING).contains(connection.getState())) {
                    // Connection already active => nothing to do.
                    // We check the state because at the moment we create only one TelecomConnection for a given call.
                    // So for group calls, onMessageChangeConnectionState will be called multiple times for the same call, and we dont want to activate a connection that was activated then put on hold.
                    return;
                }
            }
        }

        if (connection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "No connection found for peerConnectionId=" + intent.getSerializableExtra(CallService.PARAM_PEER_CONNECTION_ID) + ", callId=" + callId);
            }
            return;
        }

        connection.setActive();
        if (callId != null) {
            connectionsByCallId.computeIfAbsent(callId, key -> new ArrayList<>()).add(connection);
            pendingConnections.remove(connection);
        } else {
            Log.w(LOG_TAG, "No callId in intent");
        }
        boolean cameraMute = intent.getBooleanExtra(CallService.CALL_CAMERA_MUTE_STATE, true);

        // Setting VideoProfile.STATE_BIDIRECTIONAL when registering the call is supposed to
        // turn on the speaker automatically, but it didn't work for incoming calls during my tests
        // (for outgoing calls EXTRA_START_CALL_WITH_SPEAKERPHONE did the trick, but there's no equivalent for incoming calls).
        if (!cameraMute && !isHeadsetAvailable() && connection.getCallAudioState().getRoute() != CallAudioState.ROUTE_SPEAKER) {
            setCommunicationDevice(AudioDevice.SPEAKER_PHONE);
        }
    }

    private void onMessageTerminateCall(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMessageTerminateCall: intent=" + intent);
        }

        final TerminateReason terminateReason = (TerminateReason) intent.getSerializableExtra(CallService.CALL_SERVICE_TERMINATE_REASON);

        boolean callFound = applyToCallConnections(intent, conn -> {
            conn.setDisconnected(toDisconnectCause(terminateReason));
            conn.destroy();
        });

        if (callFound) {
            connectionsByCallId.remove((UUID) intent.getSerializableExtra(CallService.CALL_ID));
        } else {
            // No connection associated with the callId => call terminated before being accepted,
            // release pending connections.
            for (TelecomConnection connection : pendingConnections) {
                connection.setDisconnected(toDisconnectCause(terminateReason));
                connection.destroy();
            }
            pendingConnections.clear();
        }
    }

    private void onMessageCameraMuteUpdate(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMessageCameraMuteUpdate: intent=" + intent);
        }

        boolean cameraMute = intent.getBooleanExtra(CallService.CALL_CAMERA_MUTE_STATE, false);
        int videoState = cameraMute ? VideoProfile.STATE_PAUSED : VideoProfile.STATE_BIDIRECTIONAL;

        applyToCallConnections(intent, conn -> conn.setVideoState(videoState));
    }

    private void onMessageCallOnHold(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMessageCallOnHold: intent=" + intent);
        }

        applyToCallConnections(intent, TelecomConnection::setOnHold);
    }

    private void onMessageCallResumed(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMessageCallResumed: intent=" + intent);
        }

        applyToCallConnections(intent, TelecomConnection::setActive);
    }

    /**
     * Find the connections belonging to the intent's CALL_ID and apply the connectionConsumer to them.
     *
     * @param intent             an intent sent by CallService, containing the CALL_ID extra.
     * @param connectionConsumer what to do with the call's connections.
     * @return true if the CALL_ID was found in the intent, and if at least one connection belonging to the call was found.
     */
    private boolean applyToCallConnections(@NonNull Intent intent, @NonNull Consumer<TelecomConnection> connectionConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "applyToCallConnections: intent=" + intent);
        }

        Serializable callIdExtra = intent.getSerializableExtra(CallService.CALL_ID);
        if (!(callIdExtra instanceof UUID)) {
            Log.w(LOG_TAG, "Invalid value for CALL_ID extra, resume ignored: " + callIdExtra);
            return false;
        }

        UUID callId = (UUID) callIdExtra;
        List<TelecomConnection> callConnections = connectionsByCallId.get(callId);

        if (callConnections == null || callConnections.isEmpty()) {
            Log.w(LOG_TAG, "applyToCallConnections: No connection associated with call " + callId);
            return false;
        }

        for (TelecomConnection connection : callConnections) {
            connectionConsumer.accept(connection);
        }

        return true;
    }

    @Nullable
    private TelecomConnection getPendingConnection(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getTelecomConnection: intent="+intent);
        }


        UUID peerConnectionId = (UUID) intent.getSerializableExtra(CallService.CALL_SERVICE_PEER_CONNECTION_ID);

        if (peerConnectionId == null) {
            Log.e(LOG_TAG, "Could not get CALL_SERVICE_PEER_CONNECTION_ID from intent");
            return null;
        }

        for (TelecomConnection connection : pendingConnections) {
            if (peerConnectionId.equals(connection.peerConnectionId)) {
                return connection;
            }
        }

        Log.e(LOG_TAG, "No TelecomConnection found for peerConnectionId: " + peerConnectionId);

        return null;
    }

    @NonNull
    private DisconnectCause toDisconnectCause(@Nullable TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "toDisconnectCause: terminateReason=" + terminateReason);
        }

        int cause;

        if (terminateReason == null) {
            cause = DisconnectCause.OTHER;
        } else {
            switch (terminateReason) {
                case BUSY:
                    cause = DisconnectCause.BUSY;
                    break;
                case DECLINE:
                    cause = DisconnectCause.REJECTED;
                    break;
                case SUCCESS:
                    // TODO TEL: at this point we currently have no way to know who hung up the call.
                    // If the call was terminated by the peer, we should use DisconnectCause.REMOTE.
                    // Not sure what this changes in practice tho.
                    cause = DisconnectCause.LOCAL;
                    break;
                case CANCEL:
                    cause = DisconnectCause.CANCELED;
                    break;
                case TIMEOUT:
                    cause = DisconnectCause.MISSED;
                    break;
                case TRANSFER_DONE:
                    cause = DisconnectCause.CALL_PULLED;
                    break;
                case GONE:
                case REVOKED:
                case NO_PRIVATE_KEY:
                case ENCRYPT_ERROR:
                case DECRYPT_ERROR:
                case CONNECTIVITY_ERROR:
                case GENERAL_ERROR:
                    cause = DisconnectCause.ERROR;
                    break;
                default:
                    cause = DisconnectCause.OTHER;
                    break;
            }
        }

        return new DisconnectCause(cause);
    }


    /*
     * CallAudioManager
     */

    @NonNull
    @Override
    public AudioDevice getSelectedAudioDevice() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSelectedAudioDevice");
        }

        TelecomConnection connection = getActiveConnectionOrDefault();

        if (connection == null) {
            Log.e(LOG_TAG, "getSelectedAudioDevice: no connection found");
            return AudioDevice.NONE;
        }

        return TelecomUtils.getAudioDevice(connection.getCallAudioState());
    }

    @Nullable
    @Override
    public String getBluetoothDeviceName() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getBluetoothDeviceName");
        }

        TelecomConnection connection = getActiveConnectionOrDefault();

        if (connection == null) {
            Log.e(LOG_TAG, "getBluetoothDeviceName: no connection found");
            return null;
        }

        CallAudioState callAudioState = connection.getCallAudioState();

        if (callAudioState == null) {
            return null;
        }

        BluetoothDevice activeBluetoothDevice = callAudioState.getActiveBluetoothDevice();

        if (activeBluetoothDevice != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                return activeBluetoothDevice.getName();
            }
        }

        return null;
    }

    @NonNull
    @Override
    public Set<AudioDevice> getAudioDevices() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getAudioDevices");
        }

        TelecomConnection connection = getActiveConnectionOrDefault();

        if (connection == null) {
            Log.e(LOG_TAG, "getAudioDevices: no connection found");
            return Collections.emptySet();
        }

        CallAudioState callAudioState = connection.getCallAudioState();

        if (callAudioState == null) {
            return Collections.emptySet();
        }

        return TelecomUtils.getAvailableAudioDevices(callAudioState);
    }

    @Override
    public boolean isHeadsetAvailable() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isHeadsetAvailable");
        }

        TelecomConnection connection = getActiveConnectionOrDefault();

        if (connection == null) {
            Log.e(LOG_TAG, "isHeadsetAvailable: no connection found");
            return false;
        }

        CallAudioState callAudioState = connection.getCallAudioState();

        if (callAudioState == null) {
            return false;
        }

        int route = callAudioState.getSupportedRouteMask();

        return ((route & CallAudioState.ROUTE_BLUETOOTH) == CallAudioState.ROUTE_BLUETOOTH
                | (route & CallAudioState.ROUTE_WIRED_HEADSET) == CallAudioState.ROUTE_WIRED_HEADSET);
    }

    @Override
    public void setCommunicationDevice(@NonNull AudioDevice audioDevice) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCommunicationDevice: audioDevice=" + audioDevice);
        }

        TelecomConnection connection = getActiveConnectionOrDefault();

        if (connection == null) {
            Log.e(LOG_TAG, "setCommunicationDevice: no connection found");
            return;
        }

        CallAudioState callAudioState = connection.getCallAudioState();

        if (callAudioState == null) {
            return;
        }

        if (audioDevice == AudioDevice.BLUETOOTH) {
            Collection<BluetoothDevice> supportedBluetoothDevices = callAudioState.getSupportedBluetoothDevices();

            if (!supportedBluetoothDevices.isEmpty()) {
                connection.requestBluetoothAudio(supportedBluetoothDevices.iterator().next());
            }
        } else {
            connection.setAudioRoute(TelecomUtils.getAudioRoute(audioDevice));
        }
    }

    @Override
    public void setMicrophoneMute(boolean mute) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setMicrophoneMute: mute=" + mute);
        }

        //TODO: javadoc says:
        // "This method should only be used by applications that replace the platform-wide management of audio settings
        // or the main telephony application."
        // I couldn't find an alternative approach though.
        getSystemService(AudioManager.class).setMicrophoneMute(mute);
    }

    /**
     * Find the most appropriate {@link TelecomConnection} to handle audio route updates.
     *
     * @return a TelecomConnection in one of the following state, in order :
     * <ol>
     *     <li> active
     *     <li> inactive (i.e. on hold) but accepted
     *     <li> pending
     *  </ol>
     */
    @Nullable
    private TelecomConnection getActiveConnectionOrDefault() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getActiveConnectionOrDefault");
        }

        TelecomConnection inactiveConnection = null;

        for (List<TelecomConnection> acceptedConnections : connectionsByCallId.values()) {
            if (inactiveConnection == null && !acceptedConnections.isEmpty()) {
                inactiveConnection = acceptedConnections.get(0);
            }

            for (TelecomConnection connection : acceptedConnections) {
                if (connection.getState() == Connection.STATE_ACTIVE) {
                    return connection;
                }
            }
        }

        if (inactiveConnection != null) {
            // No active connection, but there's at least one accepted connection => call is most likely on hold.
            return inactiveConnection;
        }

        // No accepted connection found: most likely we're in an outgoing call which hasn't been accepted yet
        // and the user wants to change the audio route => return an arbitrary/default connection.
        return pendingConnections.isEmpty() ? null : pendingConnections.get(0);
    }

    @Override
    public void onConnectionAnswer(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionAnswer: peerConnectionId=" + peerConnectionId);
        }

        sendCallServiceAction(CallService.ACTION_ACCEPT_CALL);
    }

    @Override
    public void onConnectionReject(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionReject: peerConnectionId=" + peerConnectionId);
        }

        Bundle extras = new Bundle();
        extras.putSerializable(CallService.PARAM_TERMINATE_REASON, TerminateReason.DECLINE);
        extras.putSerializable(CallService.PARAM_PEER_CONNECTION_ID, peerConnectionId);
        sendCallServiceAction(CallService.ACTION_TERMINATE_CALL, extras);
    }

    @Override
    public void onConnectionHold(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionHold: peerConnectionId=" + peerConnectionId);
        }

        sendCallServiceAction(CallService.ACTION_HOLD_CALL);
    }

    @Override
    public void onConnectionUnhold(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionUnhold: peerConnectionId=" + peerConnectionId);
        }

        sendCallServiceAction(CallService.ACTION_RESUME_CALL);
    }

    @Override
    public void onConnectionDisconnect(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionDisconnect: peerConnectionId=" + peerConnectionId);
        }

        Bundle extras = new Bundle();
        extras.putSerializable(CallService.PARAM_TERMINATE_REASON, TerminateReason.SUCCESS);
        extras.putSerializable(CallService.PARAM_PEER_CONNECTION_ID, peerConnectionId);
        sendCallServiceAction(CallService.ACTION_TERMINATE_CALL, extras);
    }

    @Override
    public void onConnectionShowUi(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionShowUi: peerConnectionId=" + peerConnectionId);
        }

        // TODO TEL: at this point CallService should be started. Start CallActivity?
        // It should not be needed, as we've already posted the notification with FSI
        // by starting CallService.
        // (doc says we should post the notification here but it doesn't match the reference app
        // behaviour).
    }

    @Override
    public void onConnectionMute(@NonNull UUID peerConnectionId, boolean isMuted) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionMute: peerConnectionId=" + peerConnectionId + " isMuted=" + isMuted);
        }

        Bundle extras = new Bundle();
        extras.putBoolean(CallService.PARAM_AUDIO_MUTE, isMuted);
        sendCallServiceAction(CallService.ACTION_AUDIO_MUTE, extras);
    }

    @Override
    public void onConnectionCallAudioStateChanged() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionCallAudioStateChanged");
        }

        sendCallServiceAction(CallService.ACTION_AUDIO_STATE_CHANGED);
    }

    private void sendCallServiceAction(@NonNull String action) {
        sendCallServiceAction(action, null);
    }

    private void sendCallServiceAction(@NonNull String action, @Nullable Bundle extras) {

        Intent intent = new Intent(this, CallService.class);
        intent.setAction(action);
        if (extras != null) {
            intent.putExtras(extras);
        }
        startService(intent);
    }

}
