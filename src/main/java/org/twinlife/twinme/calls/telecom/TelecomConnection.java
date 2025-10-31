/*
 *  Copyright (c) 2024-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.telecom;

import android.os.Build;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Objects;
import java.util.UUID;

@RequiresApi(api = Build.VERSION_CODES.P)
public class TelecomConnection extends Connection {
    private static final String LOG_TAG = "TelecomConnection";
    private static final boolean DEBUG = false;

    public interface Listener {
        void onConnectionAnswer(@NonNull UUID peerConnectionId);

        void onConnectionReject(@NonNull UUID peerConnectionId);

        void onConnectionHold(@NonNull UUID peerConnectionId);

        void onConnectionUnhold(@NonNull UUID peerConnectionId);

        void onConnectionDisconnect(@NonNull UUID peerConnectionId);

        void onConnectionShowUi(@NonNull UUID peerConnectionId);

        void onConnectionMute(@NonNull UUID peerConnectionId, boolean isMuted);

        void onConnectionCallAudioStateChanged();
    }

    @NonNull
    final UUID peerConnectionId;
    @NonNull
    private final Listener mListener;

    public TelecomConnection(@NonNull UUID peerConnectionId, @NonNull Listener listener) {
        this.peerConnectionId = peerConnectionId;
        mListener = listener;
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCallAudioStateChanged: state=" + state);
        }

        mListener.onConnectionCallAudioStateChanged();
    }

    @Override
    public void onShowIncomingCallUi() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onShowIncomingCallUi");
        }

        setInitialized();
        setRinging();

        mListener.onConnectionShowUi(peerConnectionId);
    }

    @Override
    public void onReject() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onReject");
        }

        mListener.onConnectionReject(peerConnectionId);
    }

    /**
     * From the <a href="https://developer.android.com/develop/connectivity/telecom/selfManaged#connectionImplementation">official doc</a>:
     * <p>
     * The system may call this method when the user has disconnected a call through another in-call service such as Android Auto.
     * The system also calls this method when your call must be disconnected to allow other call to be placed, for example, if the user wants to place an emergency call.
     */
    @Override
    public void onDisconnect() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDisconnect");
        }

        mListener.onConnectionDisconnect(peerConnectionId);
    }

    @Override
    public void onAnswer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAnswer");
        }

        mListener.onConnectionAnswer(peerConnectionId);
    }

    @Override
    public void onHold() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onHold");
        }

        setOnHold();
        mListener.onConnectionHold(peerConnectionId);
    }

    @Override
    public void onUnhold() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUnhold");
        }

        setActive();
        mListener.onConnectionUnhold(peerConnectionId);
    }

    @Override
    public void onMuteStateChanged(boolean isMuted) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMuteStateChanged: isMuted=" + isMuted);
        }

       mListener.onConnectionMute(peerConnectionId, isMuted);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TelecomConnection that = (TelecomConnection) o;
        return Objects.equals(peerConnectionId, that.peerConnectionId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(peerConnectionId);
    }
}
