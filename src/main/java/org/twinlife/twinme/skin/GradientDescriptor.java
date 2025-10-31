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

public class GradientDescriptor {

    public final int fromColor;
    public final int toColor;
    public final int fromColorPressed;
    public final int toColorPressed;

    @SuppressWarnings("unused")
    public GradientDescriptor(int fromColor, int toColor, int fromColorPressed, int toColorPressed) {

        this.fromColor = fromColor;
        this.toColor = toColor;
        this.fromColorPressed = fromColorPressed;
        this.toColorPressed = toColorPressed;
    }

    @SuppressWarnings("WeakerAccess")
    public GradientDescriptor(int fromColor, int toColor) {

        this.fromColor = fromColor;
        this.toColor = toColor;
        this.fromColorPressed = fromColor;
        this.toColorPressed = toColor;
    }
}
