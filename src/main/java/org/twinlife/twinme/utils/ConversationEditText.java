/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

public class ConversationEditText extends androidx.appcompat.widget.AppCompatEditText {

    private final String[] mSupportedMimeType = new String[]{"image/png", "image/jpeg", "image/gif"};
    private KeyBoardInputCallbackListener mKeyBoardInputCallbackListener;

    public interface KeyBoardInputCallbackListener {
        void onCommitContent(InputContentInfoCompat inputContentInfo, int flags, Bundle options);
    }

    public ConversationEditText(Context context) {
        super(context);
    }

    public ConversationEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public InputConnection onCreateInputConnection(@NonNull EditorInfo outAttrs) {

        final InputConnection inputConnection = super.onCreateInputConnection(outAttrs);
        EditorInfoCompat.setContentMimeTypes(outAttrs, mSupportedMimeType);

        final InputConnectionCompat.OnCommitContentListener callback = (inputContentInfo, flags, opts) -> {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                    (flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                try {
                    inputContentInfo.requestPermission();
                } catch (Exception exception) {
                    return false;
                }
            }
            boolean supported = false;
            for (final String mimeType : mSupportedMimeType) {
                if (inputContentInfo.getDescription().hasMimeType(mimeType)) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                return false;
            }

            if (mKeyBoardInputCallbackListener != null) {
                mKeyBoardInputCallbackListener.onCommitContent(inputContentInfo, flags, opts);
            }
            return true;
        };

        return InputConnectionCompat.createWrapper(inputConnection, outAttrs, callback);
    }

    public void setKeyBoardInputCallbackListener(KeyBoardInputCallbackListener keyBoardInputCallbackListener) {

        mKeyBoardInputCallbackListener = keyBoardInputCallbackListener;
    }
}