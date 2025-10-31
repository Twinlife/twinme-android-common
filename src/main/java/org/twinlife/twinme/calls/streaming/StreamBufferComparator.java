/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.NonNull;

import java.util.Comparator;

final class StreamBufferComparator implements Comparator<StreamBuffer> {

    @Override
    public int compare(@NonNull StreamBuffer o1, @NonNull StreamBuffer o2) {

        return (int) (o1.mFirstOffset - o2.mFirstOffset);
    }
}
