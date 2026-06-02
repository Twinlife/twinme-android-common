/*
 *  Copyright (c) 2023-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */
package org.twinlife.twinme.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.actions.BackgroundAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class DownloadImageBackgroundAction extends BackgroundAction {
    private static final String LOG_TAG = "DownloadImageAction...";
    private static final boolean DEBUG = false;

    private final ImageView mImageView;
    private final File mFile;
    private final String mUrl;
    private final Handler mHandler;

    public DownloadImageBackgroundAction(@NonNull TwinmeContext twinmeContext, @NonNull ImageView imageView, @NonNull File avatarFile, @NonNull String url) {
        super(twinmeContext, BackgroundAction.DEFAULT_TIMEOUT);
        mImageView = imageView;
        mFile = avatarFile;
        mUrl = url;
        mHandler = new Handler();
    }

    protected void execute() {
        String urldisplay = mUrl;
        try (InputStream in = new java.net.URL(urldisplay).openStream()) {
            final Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) {
                mHandler.post(() -> mImageView.setImageBitmap(bitmap));

                try (FileOutputStream outStream = new FileOutputStream(mFile)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream);
                    outStream.flush();
                }
            }
        } catch (Exception e) {
            if (Logger.ERROR) {
                Log.e(LOG_TAG, "Error downloading image from " + urldisplay, e);
            }
        }
    }
}
