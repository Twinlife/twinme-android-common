/*
 *  Copyright (c) 2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.coachmark;

import android.graphics.Point;

import androidx.annotation.NonNull;

public class CoachMark {

    public enum CoachMarkTag {
        CONVERSATION_EPHEMERAL,
        ADD_PARTICIPANT_TO_CALL,
        PRIVACY,
        CONTACT_CAPABILITIES,
        CREATE_SPACE
    }

    @NonNull
    private final String mMessage;
    @NonNull
    private final CoachMarkTag mCoachMarkTag;
    private final boolean mAlignLeft;
    private final boolean mOnTop;
    @NonNull
    private final Point mFeaturePosition;
    private final int mFeatureWidth;
    private final int mFeatureHeight;
    private final float mFeatureRadius;

    public CoachMark(@NonNull String messsage, @NonNull CoachMarkTag coachMarkTag, boolean alignLeft, boolean onTop, @NonNull Point featurePosition, int featureWidth, int featureHeight, float featureRadius) {

        mMessage = messsage;
        mCoachMarkTag = coachMarkTag;
        mAlignLeft = alignLeft;
        mOnTop = onTop;
        mFeaturePosition = featurePosition;
        mFeatureWidth = featureWidth;
        mFeatureHeight = featureHeight;
        mFeatureRadius = featureRadius;
    }

    public String getMessage() {

        return mMessage;
    }

    public CoachMarkTag getCoachMarkTag() {

        return mCoachMarkTag;
    }

    public boolean isAlignLeft() {

        return mAlignLeft;
    }

    public boolean isOnTop() {

        return mOnTop;
    }

    public Point getFeaturePosition() {

        return mFeaturePosition;
    }

    public int getFeatureWidth() {

        return mFeatureWidth;
    }

    public int getFeatureHeight() {

        return mFeatureHeight;
    }

    public float getFeatureRadius() {

        return mFeatureRadius;
    }
}
