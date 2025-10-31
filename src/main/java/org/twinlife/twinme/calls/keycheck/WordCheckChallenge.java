/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.keycheck;

import androidx.annotation.NonNull;

public class WordCheckChallenge {
    public final int index;
    public final String word;
    public final boolean checker;

    public WordCheckChallenge(int index, String word, boolean checker) {
        this.index = index;
        this.word = word;
        this.checker = checker;
    }

    @NonNull
    @Override
    public String toString() {
        return "WordCheckChallenge: index=" + index + " word=" + word;
    }
}