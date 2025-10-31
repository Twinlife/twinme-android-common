/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.calls;

import static org.twinlife.twinme.calls.CallService.CALL_SERVICE_EVENT;
import static org.twinlife.twinme.calls.CallService.CALL_USER_LOCATION_LATITUDE;
import static org.twinlife.twinme.calls.CallService.CALL_USER_LOCATION_LONGITUDE;
import static org.twinlife.twinme.calls.CallService.MESSAGE_USER_LOCATION_UPDATE;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.twinlife.twinme.ui.Intents;

import java.util.List;

public class LocationManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "LocationManager";
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationRequest mLocationRequest;
    private LocationCallback mLocationCallback;

    private Location mUserLocation;
    private double mMapLatitudeDelta;
    private double mMapLongitudeDelta;
    private boolean mIsLocationShared = false;

    public void initShareLocation(@NonNull Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "initShareLocation");
        }

        if (mFusedLocationClient == null) {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(context);

            mLocationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                    .setWaitForAccurateLocation(false)
                    .setMinUpdateIntervalMillis(LocationRequest.Builder.IMPLICIT_MIN_UPDATE_INTERVAL)
                    .setMinUpdateDistanceMeters(1)
                    .build();

            mLocationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(@NonNull LocationResult locationResult) {

                    List<Location> locations = locationResult.getLocations();
                    if (!locations.isEmpty()) {
                        mUserLocation = locations.get(0);
                        sendUserLocation(context);
                        if (mIsLocationShared) {
                            sendGeolocation();
                        }
                    }
                }
            };

            startFusedLocation(context);
        }
    }

    public void startShareLocation(double mapLatitudeDelta, double mapLongitudeDelta) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startShareLocation");
        }

        mIsLocationShared = true;
        mMapLatitudeDelta = mapLatitudeDelta;
        mMapLongitudeDelta = mapLongitudeDelta;
    }

    public void stopShareLocation(boolean destroy) {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopShareLocation");
        }

        mIsLocationShared = false;

        if (destroy) {
            stopLocationUpdates();
        }
    }

    private void stopLocationUpdates() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopLocationUpdates");
        }

        if (mFusedLocationClient != null) {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
            mFusedLocationClient = null;
        }
    }

    private void sendUserLocation(Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendUserLocation");
        }

        Intent intent = new Intent(Intents.INTENT_CALL_SERVICE_MESSAGE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(CALL_SERVICE_EVENT, MESSAGE_USER_LOCATION_UPDATE);
        intent.putExtra(CALL_USER_LOCATION_LATITUDE, mUserLocation.getLatitude());
        intent.putExtra(CALL_USER_LOCATION_LONGITUDE, mUserLocation.getLongitude());
        context.sendBroadcast(intent);
    }

    private void sendGeolocation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendGeolocation");
        }

        CallState call = CallService.getState();
        Location userLocation = getUserLocation();
        if (call != null && userLocation != null && !call.getStatus().isOnHold()) {
            call.sendGeolocation(userLocation.getLongitude(), userLocation.getLatitude(), userLocation.getAltitude(), getMapLongitudeDelta(), getMapLatitudeDelta());
        }
    }

    private void startFusedLocation(Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startFusedLocation");
        }

        try {
            mFusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    // your last known location is stored in `location`
                    mUserLocation = location;
                    sendUserLocation(context);
                    if (mIsLocationShared) {
                        sendGeolocation();
                    }
                }
            });

            mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, null);
        } catch (SecurityException e) {
            Log.e(LOG_TAG, "Error while starting fused location", e);
        }
    }

    @Nullable
    public Location getUserLocation() {
        return mUserLocation;
    }

    public boolean isLocationShared() {
        return mIsLocationShared;
    }

    public double getMapLongitudeDelta() {
        return mMapLongitudeDelta;
    }

    public double getMapLatitudeDelta() {
        return mMapLatitudeDelta;
    }
}


