/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */
package org.twinlife.twinme.utils.async;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.TwinmeContext;

public interface Loader<T> {

    /**
     * Load the object or perform some long computation from the Manager thread.
     *
     * @param context       the context.
     * @param twinmeContext the twinme context.
     * @return @return the object to redraw when the image was loaded or null if there is no change.
     */
    @Nullable
    T loadObject(@NonNull Context context, @NonNull TwinmeContext twinmeContext);
}
