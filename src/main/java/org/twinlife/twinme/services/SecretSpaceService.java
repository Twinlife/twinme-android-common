/*
 *  Copyright (c) 2021-2024 twinlife SA.
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
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.List;

public class SecretSpaceService extends AbstractTwinmeService {
    private static final String LOG_TAG = "SecretSpaceService";
    private static final boolean DEBUG = false;

    private static final int SET_CURRENT_SPACE = 1 << 1;

    public interface Observer extends AbstractTwinmeService.Observer, CurrentSpaceObserver, SpaceListObserver {
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            SecretSpaceService.this.onSetCurrentSpace(space);
        }
    }

    @Nullable
    private Observer mObserver;

    public SecretSpaceService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "SpaceService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void findSecretSpaceByName(String name) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findSecretSpaceByName: " + name);
        }

        showProgressIndicator();

        TwinmeContext.Predicate<Space> filter = (Space space) -> (name.equals(space.getName()));

        mTwinmeContext.findSpaces(filter, (BaseService.ErrorCode errorCode, List<Space> spaces) -> {
            runOnGetSpaces(mObserver, spaces);
            onOperation();
        });
    }

    public void setSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: space= " + space);
        }

        long requestId = newOperation(SET_CURRENT_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: requestId=" + requestId + " space= " + space);
        }
        showProgressIndicator();
        mTwinmeContext.setCurrentSpace(requestId, space);
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        hideProgressIndicator();
    }
}