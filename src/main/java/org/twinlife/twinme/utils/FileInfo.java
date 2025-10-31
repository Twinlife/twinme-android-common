/*
 *  Copyright (c) 2018-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.ui.TwinmeApplication;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLConnection;
import java.util.Arrays;

/**
 * A MediaInfo describes a media that is selected from user's gallery, user's file repository, a temporary image or video
 * taken with the camera.
 * <p>
 * It hides the details to retrieve the mime type, the title and the path.
 */
public class FileInfo implements Parcelable  {
    private static final String LOG_TAG = "MediaInfo";
    private static final boolean DEBUG = false;

    public static final int MINIMAL_RESOLUTION = 640;
    public static final int STANDARD_RESOLUTION = 1600;
    public static final int MAX_COMPRESSION = 80;

    @NonNull
    private final Uri mUri;
    @Nullable
    private String mMimeType;
    @Nullable
    private String mTitle;
    @Nullable
    private String mPath;
    @Nullable
    private String mSize;
    private int mVideoWidth;
    private int mVideoHeight;

    @Nullable
    public static String getColumnString(@NonNull Cursor cursor, @NonNull String columnName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getColumnString columnName=" + columnName);
        }

        final int index = cursor.getColumnIndex(columnName);
        if (index < 0) {
            return null;
        }

        return cursor.getString(index);
    }

    public static long getColumnLong(@NonNull Cursor cursor, @NonNull String columnName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getColumnLong columnName=" + columnName);
        }

        final int index = cursor.getColumnIndex(columnName);
        if (index < 0) {
            return 0;
        }

        return cursor.getLong(index);
    }

    public static int getColumnInt(@NonNull Cursor cursor, @NonNull String columnName) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getColumnInt columnName=" + columnName);
        }

        final int index = cursor.getColumnIndex(columnName);
        if (index < 0) {
            return 0;
        }

        return cursor.getInt(index);
    }

    public FileInfo(@NonNull FileInfo fileInfo, @NonNull File file) {

        mUri = Uri.fromFile(file);
        mTitle = fileInfo.mTitle;
        mMimeType = fileInfo.mMimeType;
        mSize = fileInfo.mSize;
        mPath = file.getPath();
        mVideoWidth = fileInfo.getVideoWidth();
        mVideoHeight = fileInfo.getVideoHeight();
    }

    public FileInfo(@NonNull Context context, @NonNull Uri uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "MediaInfo uri=" + uri);
        }

        mUri = uri;

        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (isExternalStorageDocument(uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                String[] split = docId.split(":");
                String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {

                    mPath = Environment.getExternalStorageDirectory().getPath();
                    if (split.length == 2) {
                        mPath += "/" + split[1];
                    }
                }
                getDataColumn(context, uri, null, null);
            } else if (isDownloadsDocument(uri)) {
                getDataColumn(context, uri, null, null);
            } else if (isMediaDocument(uri)) {
                String documentId = DocumentsContract.getDocumentId(uri);
                String[] split = documentId.split(":");
                String type = split[0];

                Uri contentUri = null;
                switch (type) {
                    case "image":
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        break;
                    case "video":
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                        break;
                    case "audio":
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                        break;
                    case "document":
                        contentUri = mUri;
                        break;
                }

                if (split.length > 1) {
                    String selection = "_id=?";
                    String[] selectionArgs = new String[]{split[1]};

                    getDataColumn(context, contentUri, selection, selectionArgs);

                    if (mPath == null) {
                        getDataColumn(context, mUri, selection, selectionArgs);
                    }
                }

            } else if ("content".equalsIgnoreCase(uri.getScheme())) {
                getDataColumn(context, uri, null, null);

            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            getDataColumn(context, uri, null, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            mPath = uri.getPath();
        }

        // Last resort, build a title from the path.
        if (mTitle == null && mPath != null) {
            int index = mPath.lastIndexOf(File.separator);
            if (index != -1) {

                mTitle = mPath.substring(index + 1);
            }
        }

        // Last resort, build the mimetype from the Uri.
        if (mMimeType == null) {
            mMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("fileExtension");
            if (mMimeType == null) {
                mMimeType = context.getContentResolver().getType(mUri);
                if (mMimeType == null) {
                    mMimeType = URLConnection.guessContentTypeFromName(mUri.getPath());
                }
            }
        }

        if (isVideo()) {
            getVideoSize();
        }
    }

    public boolean isFile() {

        return "file".equalsIgnoreCase(mUri.getScheme());
    }

    @NonNull
    public Uri getUri() {

        return mUri;
    }

    @Nullable
    public String getPath() {

        return mPath;
    }

    public String getFilename() {

        return mTitle;
    }

    public String getMimeType() {

        return mMimeType;
    }

    @Nullable
    public String getSize() {

        return mSize;
    }

    public boolean isImage() {

        return mMimeType != null && mMimeType.startsWith("image");
    }

    public boolean isGIF() {

        return mMimeType != null && mMimeType.equals("image/gif");
    }

    public boolean isVideo() {

        return mMimeType != null && mMimeType.startsWith("video");
    }

    public boolean isAudio() {

        return mMimeType != null && mMimeType.startsWith("audio");
    }

    public int getVideoWidth() {

        return mVideoWidth;
    }

    public int getVideoHeight() {

        return mVideoHeight;
    }

    public void getVideoSize() {

        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(mPath);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            mVideoWidth = 0;
            if (value != null) {
                mVideoWidth = Integer.parseInt(value);
            }
            value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            mVideoHeight = 0;
            if (value != null) {
                mVideoHeight = Integer.parseInt(value);
            }
        } catch (Exception ex) {
            if (Logger.ERROR) {
                Logger.error(LOG_TAG, "Cannot save video: ", ex);
            }

            if (retriever != null) {
                try {
                    retriever.close();
                } catch (Exception ignored) {

                }
            }
        }
    }

    @Nullable
    public FileInfo saveMedia(@NonNull Context context, int sendImageSize) {

        File file = null;
        try {
            if (isImage() && sendImageSize != TwinmeApplication.SendImageSize.ORIGINAL.ordinal()) {
                file = File.createTempFile("image", ".jpg", context.getCacheDir());
                int maxSize;
                if (sendImageSize == TwinmeApplication.SendImageSize.MEDIUM.ordinal()) {
                    maxSize = STANDARD_RESOLUTION;
                } else {
                    maxSize = MINIMAL_RESOLUTION;
                }

                ResizeBitmap resizeBitmap = null;
                if (getPath() != null && isFile()) {
                    resizeBitmap = CommonUtils.resizeBitmapFromPath(getPath(), maxSize, maxSize);
                }

                if (resizeBitmap == null) {
                    try {
                        resizeBitmap = CommonUtils.resizeBitmap(context, this, maxSize, maxSize);
                    } catch (Exception | OutOfMemoryError exception) {
                        Log.e(LOG_TAG, "Cannot load bitmap for " + this + ": " + exception);
                    }
                }

                if (resizeBitmap != null && resizeBitmap.getBitmap() != null && resizeBitmap.getResizeScale() < 1) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    int compressQuality = (int) (100 * resizeBitmap.getResizeScale());
                    if (compressQuality < MAX_COMPRESSION) {
                        compressQuality = MAX_COMPRESSION;
                    }
                    resizeBitmap.getBitmap().compress(Bitmap.CompressFormat.JPEG, compressQuality, out);
                    String path = file.getAbsolutePath();
                    try (FileOutputStream outStream = new FileOutputStream(path)) {
                        outStream.write(out.toByteArray());
                    }
                    return new FileInfo(this, file);
                }
            }

            file = File.createTempFile("media", isImage() ? ".jpg" : ".mp4", context.getCacheDir());
            BaseService.ErrorCode errorCode = CommonUtils.copyUriToFile(context.getContentResolver(), getUri(), file);
            if (errorCode == BaseService.ErrorCode.SUCCESS) {
                return new FileInfo(this, file);
            }

        } catch (Exception exception) {
            Log.e(LOG_TAG, "Couldn't resize bitmap", exception);
            if (file != null && !file.delete()) {
                Log.w(LOG_TAG, "Cannot remove previous cropped image");
            }
        }
        return null;
    }

    @Nullable
    public FileInfo saveFile(@NonNull Context context) {

        String extension;
        if (mMimeType != null) {
            extension = "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(mMimeType);
        } else {
            ContentResolver contentResolver = context.getContentResolver();
            MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
            extension = "." + mimeTypeMap.getExtensionFromMimeType(contentResolver.getType(getUri()));
        }

        if (extension.equals(".")) {
            extension = ".tmp";
        }

        File file = null;
        try {
            file = File.createTempFile("file", extension, context.getCacheDir());
            BaseService.ErrorCode errorCode = CommonUtils.copyUriToFile(context.getContentResolver(), getUri(), file);

            if (errorCode == BaseService.ErrorCode.SUCCESS) {
                return new FileInfo(this, file);
            }

        } catch (Exception exception) {
            Log.e(LOG_TAG, "exception = ", exception);
            if (file != null && !file.delete()) {
                Log.w(LOG_TAG, "Cannot remove file");
            }
        }
        return null;
    }

    public static final Creator<FileInfo> CREATOR = new Creator<FileInfo>() {
        @Override
        @NonNull
        public FileInfo createFromParcel(@NonNull Parcel in) {
            return new FileInfo(in);
        }

        @Override
        @NonNull
        public FileInfo[] newArray(int size) {
            return new FileInfo[size];
        }
    };

    @Override
    public int describeContents() {

        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mUri.toString());
        dest.writeString(mMimeType);
        dest.writeString(mTitle);
        dest.writeString(mPath);
        dest.writeString(mSize);
        dest.writeInt(mVideoWidth);
        dest.writeInt(mVideoHeight);
    }

    protected FileInfo(@NonNull Parcel in) {

        mUri = Uri.parse(in.readString());
        mMimeType = in.readString();
        mTitle = in.readString();
        mPath = in.readString();
        mSize = in.readString();
        mVideoWidth = in.readInt();
        mVideoHeight = in.readInt();
    }

    //
    // Private methods
    //

    private void getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {

        final String[] projection = {
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.TITLE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
        };

        ContentResolver resolver = context.getContentResolver();

        mMimeType = resolver.getType(mUri);

        try (Cursor cursor = resolver.query(uri, projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                // Note: a data (path) can be null but we can retrieve a title or a display name.
                int index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (index >= 0 && !cursor.isNull(index)) {
                    mPath = cursor.getString(index);
                }

                index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    mTitle = cursor.getString(index);
                }

                if (mTitle == null) {
                    index = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE);
                    if (index >= 0) {
                        mTitle = cursor.getString(index);
                    }
                }

                index = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    mSize = cursor.getString(index);
                }
            }
        } catch (Exception exception) {
            Log.e(LOG_TAG, "getDataColumn: context=" + context + " uri=" + uri + " selection=" + selection + " selectionArgs=" + Arrays.toString(selectionArgs) + " exception=" + exception);
        }
    }

    private static boolean isExternalStorageDocument(Uri uri) {

        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private static boolean isDownloadsDocument(Uri uri) {

        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    private static boolean isMediaDocument(Uri uri) {

        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }
}
