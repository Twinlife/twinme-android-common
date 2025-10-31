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

public class CircularShadowDescriptor {

    public final int shadow;
    public final float imageCenterX;
    public final float imageCenterY;
    public final float imageRadius;
    public final float imageWithShadowRadius;

    @SuppressWarnings("WeakerAccess")
    public CircularShadowDescriptor(int shadow, @SuppressWarnings("SameParameterValue") float imageCenterX, float imageCenterY, float imageRadius) {

        this.shadow = shadow;
        this.imageCenterX = imageCenterX;
        this.imageCenterY = imageCenterY;
        this.imageRadius = imageRadius;
        imageWithShadowRadius = 0.5f * imageRadius / Math.max(1f - imageCenterX, 1f - imageCenterY);
    }
}
