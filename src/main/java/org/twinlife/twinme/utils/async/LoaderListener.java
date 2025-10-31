/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils.async;

import androidx.annotation.NonNull;

import java.util.List;

public interface LoaderListener<T> {

    /**
     * The async loader manager has successfully loaded some items.
     * This method is called from the main UI thread: the list of items are ready to be refreshed on the UI.
     *
     * @param items the list of items that are now loaded.
     */
    void onLoaded(@NonNull List<T> items);
}
