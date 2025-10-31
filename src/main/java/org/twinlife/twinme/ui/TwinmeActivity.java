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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Space;

@SuppressWarnings("unused")
public interface TwinmeActivity {

    enum Permission {
        CAMERA,
        RECORD_AUDIO,
        READ_EXTERNAL_STORAGE,
        WRITE_EXTERNAL_STORAGE,
        BLUETOOTH_CONNECT,
        ACCESS_FINE_LOCATION,
        ACCESS_COARSE_LOCATION,
        READ_MEDIA_AUDIO,
        POST_NOTIFICATIONS,
        ACCESS_BACKGROUND_LOCATION,
    }

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

    void runOnUiThread(Runnable runnable);

    default void onExecutionError(ErrorCode errorCode){
        //NOOP, implemented in AbstractTwinmeActivity
    }

    default void onSetCurrentSpace(Space space){
        //NOOP, override in activities when needed
    }

    ContentResolver getContentResolver();

    boolean checkPermissions(@NonNull Permission[] permissions);

    boolean checkPermissionsWithoutRequest(@NonNull Permission[] permissions);

    void onRequestPermissions(@NonNull Permission[] grantedPermissions);

    void toast(@NonNull String message);

    void message(@NonNull String message, long timeout, @Nullable MessageCallback messageCallback);

    void messageSettings(@NonNull String message, long timeout, @Nullable SettingsMessageCallback messageCallback);

    void error(@NonNull String message, @Nullable Runnable errorCallback);

    void onError(ErrorCode errorCode, @Nullable String message, @Nullable Runnable errorCallback);
}
