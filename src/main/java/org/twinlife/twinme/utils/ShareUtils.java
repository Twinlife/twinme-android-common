/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinme.ui.Intents;

import java.util.ArrayList;
import java.util.List;

public class ShareUtils {

    @Nullable
    public static CharSequence getSharedText(@NonNull Intent intent) {

        if (!intent.hasExtra(Intent.EXTRA_TEXT)) {
            return null;
        }

        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            // Multiple items shared -> EXTRA_TEXT may be an array of lines.
            CharSequence[] lines = intent.getCharSequenceArrayExtra(Intent.EXTRA_TEXT);
            if (lines != null) {
                SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    CharSequence line = lines[i];
                    stringBuilder.append(line);
                    if (i < lines.length - 1) {
                        stringBuilder.append("\n");
                    }
                }
                return stringBuilder;
            }
        }

        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    }

    @NonNull
    public static List<Uri> getSharedFiles(@NonNull Intent intent) {

        if (intent.hasExtra(Intents.INTENT_DIRECT_SHARE_URIS)) {
            // Shared files have already been imported by ShareActivity
            List<Uri> directShareUris = intent.getParcelableArrayListExtra(Intents.INTENT_DIRECT_SHARE_URIS);
            if (directShareUris != null) {
                return directShareUris;
            }
        }

        // Look for files shared by other apps
        List<Uri> uris = new ArrayList<>();
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()) && intent.hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Parcelable> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Parcelable parcelable : streams) {
                    if (parcelable instanceof Uri) {
                        uris.add((Uri) parcelable);
                    }
                }
            }
        } else if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.hasExtra(Intent.EXTRA_STREAM)) {
            Parcelable stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) {
                uris.add((Uri) stream);
            }
        }

        return uris;
    }
}
