/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.keycheck;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

class KeyCheckSession {
    private static final String LOG_TAG = "KeyCheckSession";
    private static final boolean DEBUG = false;

    private static final int NUM_WORDS = 5;

    @NonNull
    private final List<String> mWords;
    private final boolean initiator;
    @NonNull
    private final Boolean[] mResults = new Boolean[NUM_WORDS];

    private int mCurrentWordIndex = 0;

    @Nullable
    private Boolean mPeerResult = null;
    private boolean mTerminateSent = false;

    KeyCheckSession(@NonNull List<String> words, boolean initiator) throws IllegalArgumentException {
        if (words.size() != NUM_WORDS) {
            throw new IllegalArgumentException("words must contain " + NUM_WORDS + "words but contains " + words.size() + "words.");
        }

        this.mWords = words;
        this.initiator = initiator;
    }

    @NonNull
    synchronized WordCheckChallenge getCurrentWord() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentWord, currentWordIndex=" + mCurrentWordIndex);
        }

        if (mResults[mCurrentWordIndex] != null && mCurrentWordIndex < NUM_WORDS - 1) {
            mCurrentWordIndex++;
        }

        // The initiator checks odd words (1, 3, 5), the other checks even words (2, 4).
        boolean checker = initiator == (mCurrentWordIndex % 2 == 0);

        return new WordCheckChallenge(mCurrentWordIndex, mWords.get(mCurrentWordIndex), checker);
    }

    @Nullable
    synchronized WordCheckChallenge getPeerError() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPeerError");
        }

        // Start from the first word checked by the peer.
        int start = initiator ? 1 : 0;

        for (int i = start; i < mResults.length; i += 2) {
            if (Boolean.FALSE.equals(mResults[i])) {
                return new WordCheckChallenge(i, mWords.get(i), true);
            }
        }

        return null;
    }

    void addPeerResult(@NonNull WordCheckResult result) {

        boolean consistencyCheck = initiator != (result.wordIndex % 2 == 0);

        if (!consistencyCheck) {
            Log.e(LOG_TAG, "Checker for " + result + " is local, but result was added as peer, ignoring");
            return;
        }
        addResult(result);
    }

    void addLocalResult(@NonNull WordCheckResult result) {

        boolean consistencyCheck = initiator == (result.wordIndex % 2 == 0);

        if (!consistencyCheck) {
            Log.e(LOG_TAG, "Checker for " + result + " is the peer, but result was added as local, ignoring");
            return;
        }
        addResult(result);
    }

    synchronized boolean isDone() {
        for (Boolean result : mResults) {
            if (result == null) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    synchronized Boolean isOK() {
        if (!isDone()) {
            return null;
        }

        for (Boolean result : mResults) {
            if (Boolean.FALSE.equals(result)) {
                return false;
            }
        }

        return true;
    }

    synchronized boolean isTerminateSent() {
        return mTerminateSent;
    }

    synchronized boolean getAndSetTerminateSent() {
        boolean previousValue = mTerminateSent;
        mTerminateSent = true;
        return previousValue;
    }

    @Nullable
    synchronized Boolean getPeerResult() {
        return mPeerResult;
    }

    synchronized void setPeerResult(@Nullable Boolean peerResult) {
        mPeerResult = peerResult;
    }

    private synchronized void addResult(@NonNull WordCheckResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "addResult : result= " + result);
        }

        if (mResults[result.wordIndex] != null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "word " + result.wordIndex + " already checked: " + mResults[result.wordIndex]);
            }
        }

        mResults[result.wordIndex] = result.ok;
    }

}
