/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.skin;

import android.graphics.Typeface;

public class TextStyle {

    public final Typeface typeface;
    public final float size;

    TextStyle(Typeface typeface, float size) {

        this.typeface = typeface;
        this.size = size;
    }
}
