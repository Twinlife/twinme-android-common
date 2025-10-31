/*
 *  Copyright (c) 2015-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Denis Campredon (Denis.Campredon@twinlife-systems.com)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */
package org.twinlife.twinme.skin;

import android.graphics.Bitmap;

public class CircularImageDescriptor {

    public final Bitmap image;
    public final float centerX;
    public final float centerY;
    public final float radius;
    public final boolean hasBorder;
    public final int borderColor;
    public final float borderThickness;
    public final float borderInset;

    public CircularImageDescriptor(Bitmap bitmap, float centerX, float centerY, float radius) {

        this.image = bitmap;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        hasBorder = false;
        borderColor = 0;
        borderThickness = 0;
        borderInset = 0;
    }

    public CircularImageDescriptor(Bitmap bitmap, float centerX, float centerY, float radius, int borderColor, float borderThickness, float borderInset) {

        this.image = bitmap;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        hasBorder = true;
        this.borderColor = borderColor;
        this.borderThickness = borderThickness;
        this.borderInset = borderInset;
    }
}
