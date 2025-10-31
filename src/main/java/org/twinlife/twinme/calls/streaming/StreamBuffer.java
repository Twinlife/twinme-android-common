/*
 *  Copyright (c) 2022-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.calls.streaming;

import androidx.annotation.NonNull;

final class StreamBuffer {
    static final int BUFFER_SIZE = 8 * 1024;
    static final byte[] empty = new byte[0];

    final byte[] mBuffer;
    final long mFirstOffset;
    final long mLastOffset;

    StreamBuffer(long offset) {

        this.mFirstOffset = offset;
        this.mLastOffset = offset;
        this.mBuffer = empty;
    }

    StreamBuffer(long offset, @NonNull byte[] buffer) {

        this.mFirstOffset = offset;
        this.mLastOffset = offset + buffer.length;
        this.mBuffer = buffer;
    }

    StreamBuffer(long offset, @NonNull byte[] buffer, int size) {

        this.mFirstOffset = offset;
        this.mLastOffset = offset + size;
        this.mBuffer = buffer;
    }

    int size() {

        return (int) (mLastOffset - mFirstOffset);
    }

    @Override
    @NonNull
    public String toString() {

        return "StreamBuffer[" + mFirstOffset + ".." + mLastOffset + "]";
    }
}
