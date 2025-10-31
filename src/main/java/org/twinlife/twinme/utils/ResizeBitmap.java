/*
 *  Copyright (c) 2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

public class ResizeBitmap {

    @Nullable
    private Bitmap mBitmap;
    private float mResizeScale = 0f;

    public Bitmap getBitmap() {

        return mBitmap;
    }

    public void setBitmap(Bitmap bitmap) {

        mBitmap = bitmap;
    }

    public float getResizeScale() {

        return mResizeScale;
    }

    public void setResizeScale(float scale) {

        mResizeScale = scale;
    }
}
