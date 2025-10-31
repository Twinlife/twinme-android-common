/*
 *  Copyright (c) 2024-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.graphics.Point;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Date;

public class AppStateInfo {

    public enum InfoFloatingViewState {
        DEFAULT,
        EXTEND
    }

    public enum InfoFloatingViewType {
        CONNECTED,
        CONNECTION_IN_PROGRESS,
        NO_SERVICES,
        OFFLINE
    }

    private static final int ADD_TIME_INTERVAL = 5;

    private InfoFloatingViewState mInfoFloatingViewState;
    private InfoFloatingViewType mInfoFloatingViewType;
    private Date mExpirationDate;

    @Nullable
    private Point mPosition;

    public AppStateInfo(InfoFloatingViewState infoFloatingViewState, InfoFloatingViewType infoFloatingViewType, @Nullable Point position) {

        mInfoFloatingViewState = infoFloatingViewState;
        mInfoFloatingViewType = infoFloatingViewType;
        mPosition = position;

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(new Date().getTime());
        calendar.add(Calendar.SECOND, 5);

        mExpirationDate = calendar.getTime();
    }

    public InfoFloatingViewState getState() {

        return mInfoFloatingViewState;
    }

    public void setInfoFloatingViewState(InfoFloatingViewState infoFloatingViewState) {

        mInfoFloatingViewState = infoFloatingViewState;
    }

    public InfoFloatingViewType getType() {

        return mInfoFloatingViewType;
    }

    public void setInfoFloatingViewType(InfoFloatingViewType infoFloatingViewType) {

        // Change the expiration time if the state changed or if we are not connected.
        // If the user moves between activities, we are called again but we don't want
        // the expiration time to be changed as it creates a persistent info floating view.
        if (infoFloatingViewType != mInfoFloatingViewType) {
            mInfoFloatingViewType = infoFloatingViewType;
            updateExpirationTime();
        } else if (infoFloatingViewType != InfoFloatingViewType.CONNECTED) {
            updateExpirationTime();
        }
    }

    public Point position() {

        return mPosition;
    }

    public void setPosition(Point position) {

        mPosition = position;
    }

    public void updateExpirationTime() {

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(new Date().getTime());
        calendar.add(Calendar.SECOND, ADD_TIME_INTERVAL);

        mExpirationDate = calendar.getTime();
    }

    public boolean displayInfoFloatingView() {

        return mInfoFloatingViewType != InfoFloatingViewType.CONNECTED || mExpirationDate.after(new Date());
    }

    @NonNull
    @Override
    public String toString() {

        return "AppState[" + mInfoFloatingViewType + " " + mInfoFloatingViewState + "]";
    }
}
