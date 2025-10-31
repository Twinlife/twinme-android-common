/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


/**
 * NOOP implementation for Twinme
 */
public class LocationManager {
    public void initShareLocation(@NonNull Context context) {
        //NOOP
    }

    public void startShareLocation(double mapLatitudeDelta, double mapLongitudeDelta) {
        //NOOP

    }

    public void stopShareLocation(boolean destroy) {
        //NOOP

    }

    private void stopLocationUpdates() {
        //NOOP

    }

    private void sendUserLocation(Context context) {
        //NOOP

    }

    private void sendGeolocation() {
    }

    private void startFusedLocation(Context context) {
        //NOOP

    }

    @Nullable
    public Location getUserLocation() {
        return null;
    }

    public boolean isLocationShared() {
        return false;
    }

    public double getMapLongitudeDelta() {
        return 0;
    }

    public double getMapLatitudeDelta() {
        return 0;
    }
}


