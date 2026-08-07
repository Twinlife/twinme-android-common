/*
 *  Copyright (c) 2014-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.ui;

import android.content.ContentResolver;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.ErrorCode;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Space;

@SuppressWarnings("unused")
public interface TwinmeActivity {

    interface MessageCallback {

        void onClick();

        void onTimeout();
    }

    interface SettingsMessageCallback {

        void onCancelClick();

        void onSettingsClick();

        void onTimeout();
    }

    class DefaultMessageCallback implements MessageCallback {

        final int positiveButtonId;

        @SuppressWarnings("WeakerAccess")
        public DefaultMessageCallback(int positiveButtonId) {

            this.positiveButtonId = positiveButtonId;
        }

        @Override
        public void onClick() {
        }

        @Override
        public void onTimeout() {
        }
    }

    @NonNull
    TwinmeApplication getTwinmeApplication();

    @NonNull
    TwinmeContext getTwinmeContext();

    // Implemented by Activity
    void runOnUiThread(Runnable runnable);

    Context getApplicationContext();

    ContentResolver getContentResolver();

    default void onExecutionError(ErrorCode errorCode){
        //NOOP, implemented in AbstractTwinmeActivity
    }

    default void onSetCurrentSpace(Space space){
        //NOOP, override in activities when needed
    }

    default void onExecutionSuccess(){
        //NOOP, implemented in AbstractTwinmeActivity
    }

    boolean checkPermissions(@NonNull Permission[] permissions);

    boolean checkPermissionsWithoutRequest(@NonNull Permission[] permissions);

    void onRequestPermissions(@NonNull Permission[] grantedPermissions);

    void toast(@NonNull String message);

    void message(@NonNull String message, long timeout, @Nullable MessageCallback messageCallback);

    void messageSettings(@NonNull String message, long timeout, @Nullable SettingsMessageCallback messageCallback);

    void error(@NonNull String message, @Nullable Runnable errorCallback);

    void onError(ErrorCode errorCode, @Nullable String message, @Nullable Runnable errorCallback);
}
