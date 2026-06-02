/*
 *  Copyright (c) 2018-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import org.twinlife.twinme.actions.BackgroundAction;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SaveTwincodeBackgroundAction extends BackgroundAction {
    private static final String LOG_TAG = "SaveTwincodeBackAction";
    private static final boolean DEBUG = false;

    private final ContentResolver mResolver;
    private final WeakReference<TwinmeActivity> mActivityWeakReference;
    private final Bitmap mQRCodeBitmap;
    @StringRes
    private final int mMessageId;

    public SaveTwincodeBackgroundAction(@NonNull TwinmeActivity activity, @NonNull Bitmap QRCodeBitmap, @StringRes int messageId) {
        super(activity.getTwinmeContext(), BackgroundAction.DEFAULT_TIMEOUT);

        mResolver = activity.getContentResolver();
        mActivityWeakReference = new WeakReference<>(activity);
        mQRCodeBitmap = QRCodeBitmap;
        mMessageId = messageId;
    }

    protected void execute() {
        if (DEBUG) {
            Log.d(LOG_TAG, "execute");
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyyyyHHmmss", Locale.US);
        String fileName = "twincode_" + dateFormat.format(new Date());

        ContentValues values = new ContentValues();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        values.put(MediaStore.Images.Media.TITLE, fileName);
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
        values.put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }

        Uri item = mResolver.insert(base, values);
        if (item != null) {
            try (ParcelFileDescriptor parcelFileDescriptor = mResolver.openFileDescriptor(item, "w")) {
                if (parcelFileDescriptor != null) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    mQRCodeBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                    try (InputStream inputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
                         OutputStream outputStream = new FileOutputStream(parcelFileDescriptor.getFileDescriptor())) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    }
                    mResolver.update(item, values, null, null);

                    values.clear();
                }
            } catch (Exception exception) {
                Log.e(LOG_TAG, "Error occurred while saving QRcode", exception);
            }
        }

        final TwinmeActivity activity = mActivityWeakReference.get();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                Toast.makeText(activity.getApplicationContext(), mMessageId, Toast.LENGTH_SHORT).show();
            });
        }
    }
}
