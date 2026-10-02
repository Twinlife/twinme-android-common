/*
 *  Copyright (c) 2020-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.skin;

public class UICustomColor {
    private static int sItemId = 0;

    private final long mItemId;

    private final String mColor;

    private boolean mSelected;

    public UICustomColor(String color) {
        mItemId = sItemId++;

        mColor = color;
        mSelected = false;
    }

    public boolean useDefaultColor() {

        return mColor == null;
    }

    public String getColor() {

        if (mColor == null) {
            return Design.DEFAULT_COLOR;
        }
        return mColor;
    }

    public String getStringColor() {

        return mColor;
    }

    public long getItemId() {

        return mItemId;
    }

    public boolean isSelected() {

        return mSelected;
    }

    public void setSelected(boolean selected) {

        mSelected = selected;
    }
}