/*
 *  Copyright (c) 2022-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.SpaceSettings;
import org.twinlife.twinme.ui.TwinmeActivity;

public class SpaceSettingsService extends AbstractTwinmeService {
    private static final String LOG_TAG = "SpaceSettingsService";
    private static final boolean DEBUG = false;

    private static final int UPDATE_DEFAULT_SPACE_SETTINGS = 1 << 20;

    public interface Observer extends AbstractTwinmeService.Observer {

        void onUpdateDefaultSpaceSettings(SpaceSettings spaceSettings);
    }

    @Nullable
    private Observer mObserver;

    public SpaceSettingsService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "EditSpaceService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void updateDefaultSpaceSettings(SpaceSettings spaceSettings) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateDefaultSpaceSettings: spaceSettings= " + spaceSettings);
        }

        long requestId = newOperation(UPDATE_DEFAULT_SPACE_SETTINGS);
        if (DEBUG) {
            Log.d(LOG_TAG, "updateSpace: requestId=" + requestId + " spaceSettings= " + spaceSettings);
        }
        showProgressIndicator();

        mTwinmeContext.saveDefaultSpaceSettings(spaceSettings, (BaseService.ErrorCode status, SpaceSettings settings) -> runOnUiThread(() -> {
            if (mObserver != null && settings != null) {
                mObserver.onUpdateDefaultSpaceSettings(settings);
            }
            onOperation();
        }));
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }
}
