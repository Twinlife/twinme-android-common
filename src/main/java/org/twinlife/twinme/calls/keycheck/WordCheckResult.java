/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.keycheck;

import androidx.annotation.NonNull;

import java.io.Serializable;

public class WordCheckResult implements Serializable {
    public final int wordIndex;
    public final boolean ok;

    public WordCheckResult(int wordIndex, boolean ok) {
        this.wordIndex = wordIndex;
        this.ok = ok;
    }

    @Override
    @NonNull
    public String toString(){
        return "WordCheckResult[wordIndex="+wordIndex+" ok="+ ok+"]";
    }
}