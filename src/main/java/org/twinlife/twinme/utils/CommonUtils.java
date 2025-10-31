/*
 *  Copyright (c) 2015-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Thibaud David (contact@thibauddavid.com)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Yannis Le Gal (Yannis.LeGal@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.Patterns;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;
import androidx.core.app.Person;
import androidx.core.content.LocusIdCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.text.TextUtilsCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.Twinlife;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.ui.Intents;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.twinlife.twinlife.AndroidImageTools.IMAGE_JPEG_QUALITY;

public class CommonUtils {
    private static final String LOG_TAG = "CommonUtils";
    private static final boolean DEBUG = false;

    private static final int MAX_ENTRIES = 64;

    private static final String EMOJI_HAPPY_CODE = "\uD83D\uDE00";
    private static final String EMOJI_SAD_CODE = "\uD83D\uDE41";
    public static final String SMILEY_HAPPY = ":-)";
    public static final String SMILEY_SAD = ":-(";

    public static final String BOLD_STYLE_SYMBOL = "*";
    public static final String ITALIC_STYLE_SYMBOL = "_";
    public static final String STRIKE_THROUGH_STYLE_SYMBOL = "~";

    public static final Pattern BOLD_PATTERN = Pattern.compile("\\*([^*]+)\\*");
    public static final Pattern ITALIC_PATTERN = Pattern.compile("\\_([^_]+)\\_");
    public static final Pattern STRIKE_THROUGH_PATTERN = Pattern.compile("\\~([^~]+)\\~");

    public static final int MAX_IMAGE_SIZE = 5120;   // Maximum dimension we allow for the Bitmap image.

    public static final String CATEGORY_SHARE_TARGET = "org.twinlife.twinme.sharing.CATEGORY_SHARE_TARGET";

    private static final long ONE_YEAR = 60 * 60 * 24 * 365;

    private static final String IPV6_REGEX =
            "(?i)\\b(" +
                    "(?:https?://)?\\[?" +
                    "(" +
                    "([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|" +
                    "([0-9a-f]{1,4}:){1,7}:|" +
                    "(:[0-9a-f]{1,4}){1,7}|" +
                    "([0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}|" +
                    "([0-9a-f]{1,4}:){1,5}(:[0-9a-f]{1,4}){1,2}|" +
                    "([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,3}|" +
                    "([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,4}|" +
                    "([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,5}|" +
                    "[0-9a-f]{1,4}:((:[0-9a-f]{1,4}){1,6})|" +
                    "::" +
                    ")" +
                    "]?" +
                    "(/[\\w\\-.~%/?#=&]*)?" +
                    ")\\b";

    public static final Pattern IPV6_PATTERN = Pattern.compile(IPV6_REGEX);

    public static int parseColor(@Nullable String color, int defaultColor) {

        if (color == null || color.isEmpty()) {
            return defaultColor;
        }
        try {
            return Color.parseColor(color);

        } catch (IllegalArgumentException exception) {
            try {
                return Color.parseColor('#' + color);
            } catch (IllegalArgumentException ignored) {
                return defaultColor;
            }
        }
    }

    private static class Key {

        @NonNull
        final String path;

        final int maxWidth;
        final int maxHeight;
        Key(@NonNull String path, int maxWidth, int maxHeight) {

            this.path = path;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }

        @Override
        public boolean equals(Object object) {

            if (object == this) {

                return true;
            }

            if (!(object instanceof Key)) {

                return false;
            }

            Key key = (Key) object;

            return path.equals(key.path) && maxWidth == key.maxWidth && maxHeight == key.maxHeight;
        }

        @Override
        public int hashCode() {

            int result = 17;
            result = 31 * result + path.hashCode();
            result = 31 * result + maxWidth;
            result = 31 * result + maxHeight;

            return result;
        }

    }
    private static class Range {

        private int mStart;
        private int mEnd;
        Range(int start, int end) {
            mStart = start;
            mEnd = end;
        }

        public int getStart() {

            return mStart;
        }

        public void setStart(int start) {

            mStart = start;
        }

        public int getEnd() {

            return mEnd;
        }

        public void setEnd(int end) {

            mEnd = end;
        }

        public int getLength() {

            return mEnd - mStart;
        }

    }
    private static final LruCache<Key, WeakReference<BitmapDrawable>> sBitmapDrawableCache = new LruCache<>(MAX_ENTRIES);

    @Nullable
    public static UUID UUIDFromString(@Nullable String value) {

        return org.twinlife.twinlife.util.Utils.UUIDFromString(value);
    }

    /**
     * Copy the content by using the resolver in the target file.
     *
     * @param resolver the resolver to use.
     * @param path the source URI path to copy.
     * @param toPath the target destination file.
     * @return SUCCESS if the copy succeeded or an error code.
     */
    @NonNull
    public static BaseService.ErrorCode copyUriToFile(@NonNull ContentResolver resolver, @NonNull Uri path, @NonNull File toPath) {

        return org.twinlife.twinlife.util.Utils.copyUriToFile(resolver, path, toPath);
    }

    @Nullable
    public static File saveBitmap(@NonNull Bitmap bitmap) {

        try {
            File tmpFile = File.createTempFile("bitmap", ".jpg");
            try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, fileOutputStream);
            }
            return tmpFile;

        } catch (IOException e) {
            if (Logger.ERROR) {
                Logger.error(LOG_TAG, "Exception: ", e);
            }
            return null;
        }
    }

    public static int calculateInSampleSize(@NonNull BitmapFactory.Options options) {

        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        while ((height / inSampleSize) > Twinlife.MAX_AVATAR_HEIGHT || (width / inSampleSize) > Twinlife.MAX_AVATAR_WIDTH) {

            inSampleSize *= 2;
        }

        return inSampleSize;
    }

    @Nullable
    public static Bitmap getScaledAvatar(@NonNull Uri uri) {

        String path = uri.getPath();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        options.inSampleSize = calculateInSampleSize(options);
        if (options.inSampleSize > 1) {
            options.inSampleSize = options.inSampleSize / 2;
        }
        options.inJustDecodeBounds = false;

        Bitmap result = BitmapFactory.decodeFile(path, options);
        if (result != null) {
            // Use createScaledBitmap to make a better thumbnail definition with the max avatar width and height.
            try {
                if (result.getWidth() > Twinlife.MAX_AVATAR_WIDTH || result.getHeight() > Twinlife.MAX_AVATAR_HEIGHT) {
                    double width = result.getWidth();
                    double height = result.getHeight();
                    double scale = Math.min((double) Twinlife.MAX_AVATAR_WIDTH / width, (double) Twinlife.MAX_AVATAR_HEIGHT / height);
                    int newWidth = (int) Math.round(scale * width);
                    int newHeight = (int) Math.round(scale * height);
                    result = Bitmap.createScaledBitmap(result, newWidth, newHeight, true);
                }
            } catch (Throwable ex) {
                return null;
            }
        }
        return result;
    }

    @Nullable
    public static Bitmap getScaledAvatar(@NonNull Resources resources, int resId) {

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, resId, options);
        options.inSampleSize = calculateInSampleSize(options);
        options.inJustDecodeBounds = false;

        return BitmapFactory.decodeResource(resources, resId, options);
    }

    @Nullable
    public static BitmapDrawable getBitmapDrawable(@NonNull Context context, @NonNull String path, int maxWidth, int maxHeight) {

        Key key = new Key(path, maxWidth, maxHeight);
        WeakReference<BitmapDrawable> cachedEntry = sBitmapDrawableCache.get(key);
        BitmapDrawable bitmapDrawable = null;
        if (cachedEntry != null) {
            bitmapDrawable = cachedEntry.get();
            if (bitmapDrawable != null) {
                return bitmapDrawable;
            }
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;

        try {
            ExifInterface exifInterface = new ExifInterface(path);
            orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "getBitmapDrawable context=" + context + " path=" + path + " maxWidth=" + maxWidth + " maxHeight=" + maxHeight + " exception=" + exception);
        } catch (OutOfMemoryError exception) {
            return null;
        }

        switch (orientation) {
            case ExifInterface.ORIENTATION_TRANSPOSE:
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSVERSE:
            case ExifInterface.ORIENTATION_ROTATE_270:
                int max = maxWidth;
                //noinspection SuspiciousNameCombination
                maxWidth = maxHeight;
                maxHeight = max;
                break;

            default:
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError exception) {
            return null;
        }

        int inSampleSize = 1;

        // Scale the image if we have a max width or height.
        if (maxWidth > 0 && maxHeight > 0) {
            while (options.outWidth / inSampleSize > maxWidth || options.outHeight / inSampleSize > maxHeight) {
                inSampleSize *= 2;
            }

            // For a max width/height < max image size (such as 256), we accept and need an image
            // that is a little bit larger than the max size so that we get some reasonable resolution.
            if (inSampleSize > 1 && maxWidth < MAX_IMAGE_SIZE && maxHeight < MAX_IMAGE_SIZE) {
                inSampleSize = inSampleSize / 2;
            }
        }

        Matrix matrix = null;
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix = new Matrix();
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix = new Matrix();
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix = new Matrix();
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix = new Matrix();
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix = new Matrix();
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix = new Matrix();
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix = new Matrix();
                matrix.setRotate(-90);
                break;
            default:
        }

        try {
            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap != null) {
                if (matrix != null) {
                    Bitmap lBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    bitmap.recycle();
                    bitmapDrawable = new BitmapDrawable(context.getResources(), lBitmap);
                } else {
                    bitmapDrawable = new BitmapDrawable(context.getResources(), bitmap);
                }
                if (DEBUG) {
                    Log.e(LOG_TAG, "Loaded bitmap " + path + " " + bitmap.getWidth() + "x" + bitmap.getHeight());
                }

                // Don't put in the cache an image that was not scaled.
                if (maxWidth > 0 && maxHeight > 0) {
                    sBitmapDrawableCache.put(key, new WeakReference<>(bitmapDrawable));
                }
            }
        } catch (Exception | OutOfMemoryError exception) {
            Log.e(LOG_TAG, "getBitmapDrawable context=" + context + " path=" + path + " maxWidth=" + maxWidth + " maxHeight=" + maxHeight + " exception=" + exception);
        }

        return bitmapDrawable;
    }

    @Nullable
    public static ResizeBitmap resizeBitmapFromPath(@NonNull String path, int maxWidth, int maxHeight) {
        if (DEBUG) {
            Log.d(LOG_TAG, "resizeBitmapFromPath: " + path + " maxWidth=" + maxWidth + " maxHeight=" + maxHeight);
        }

        ResizeBitmap resizeBitmap = new ResizeBitmap();
        int orientation = ExifInterface.ORIENTATION_NORMAL;

        try {
            ExifInterface exifInterface = new ExifInterface(path);
            orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "resizeBitmapFromPath" + path + " maxWidth=" + maxWidth + " maxHeight=" + maxHeight + " exception=" + exception);
        } catch (OutOfMemoryError exception) {
            return null;
        }

        switch (orientation) {
            case ExifInterface.ORIENTATION_TRANSPOSE:
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSVERSE:
            case ExifInterface.ORIENTATION_ROTATE_270:
                int max = maxWidth;
                //noinspection SuspiciousNameCombination
                maxWidth = maxHeight;
                maxHeight = max;
                break;

            default:
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError exception) {
            return null;
        }

        int inSampleSize = 1;
        float resizeScale;
        if (options.outWidth > options.outHeight) {
            resizeScale = maxWidth / (float) options.outWidth;
        } else {
            resizeScale = maxHeight / (float) options.outHeight;
        }

        resizeBitmap.setResizeScale(resizeScale);

        if (maxWidth > 0 && maxHeight > 0) {
            while (options.outWidth / inSampleSize > maxWidth || options.outHeight / inSampleSize > maxHeight) {
                inSampleSize++;
            }

            // Get the image that is a little bit bigger than expected and we will do a createScaledBitmap()
            // to get a better final resolution.
            if (inSampleSize > 1) {
                inSampleSize--;
            }
        }

        Matrix matrix = null;
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix = new Matrix();
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix = new Matrix();
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix = new Matrix();
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix = new Matrix();
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix = new Matrix();
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix = new Matrix();
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix = new Matrix();
                matrix.setRotate(-90);
                break;
            default:
        }

        Bitmap bitmap;
        try {
            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;

            bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap != null) {
                // The inSampleSize is computed so that the image is a little bit bigger.  Since this is a power of 2
                // reduction, we get the final image by scaling it to the maxWidth x maxHeight and we must compute
                // the new scale factor for that reduction.
                if (matrix != null) {
                    if (resizeScale >= 1.0) {
                        Bitmap lBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        bitmap.recycle();
                        resizeBitmap.setBitmap(lBitmap);
                    } else {
                        if (bitmap.getWidth() > bitmap.getHeight()) {
                            resizeScale = maxWidth / (float) bitmap.getWidth();
                        } else {
                            resizeScale = maxHeight / (float) bitmap.getHeight();
                        }

                        // First scale to the final dimension then rotate (doing both didn't worked).
                        Bitmap lBitmap = Bitmap.createScaledBitmap(bitmap, (int) (bitmap.getWidth() * resizeScale), (int) (bitmap.getHeight() * resizeScale), true);
                        bitmap.recycle();
                        bitmap = Bitmap.createBitmap(lBitmap, 0, 0, lBitmap.getWidth(), lBitmap.getHeight(), matrix, true);
                        lBitmap.recycle();
                        resizeBitmap.setBitmap(bitmap);
                    }
                } else {
                    if (resizeScale >= 1.0) {
                        resizeBitmap.setBitmap(bitmap);
                    } else {
                        if (bitmap.getWidth() > bitmap.getHeight()) {
                            resizeScale = maxWidth / (float) bitmap.getWidth();
                        } else {
                            resizeScale = maxHeight / (float) bitmap.getHeight();
                        }

                        Bitmap lBitmap = Bitmap.createScaledBitmap(bitmap, (int) (bitmap.getWidth() * resizeScale), (int) (bitmap.getHeight() * resizeScale), true);
                        bitmap.recycle();
                        resizeBitmap.setBitmap(lBitmap);
                    }
                }
            }
        } catch (Exception | OutOfMemoryError exception) {
            Log.e(LOG_TAG, "resizeBitmapFromPath " + path + " maxWidth=" + maxWidth + " maxHeight=" + maxHeight + " exception=" + exception);
        }

        return resizeBitmap;
    }

    public static ResizeBitmap resizeBitmap(@NonNull Context context, @NonNull FileInfo fileInfo, int maxWidth, int maxHeight) {
        if (DEBUG) {
            Log.d(LOG_TAG, "resizeBitmap: " + context + " mediaInfo=" + fileInfo + " maxWidth=" + maxWidth + " maxHeight=" + maxHeight);
        }

        ResizeBitmap resizeBitmap = new ResizeBitmap();
        Bitmap bitmap;

        try {
            bitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), fileInfo.getUri());

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;

            float resizeScale;
            if (bitmap.getWidth() > bitmap.getHeight()) {
                resizeScale = maxWidth / (float) bitmap.getWidth();
            } else {
                resizeScale = maxHeight / (float) bitmap.getHeight();
            }

            resizeBitmap.setResizeScale(resizeScale);

            int orientation = getOrientation(context, fileInfo.getUri());
            int rotation = getRotationDegrees(orientation);
            if (rotation != 0) {
                bitmap = rotateBitmap(bitmap, rotation);
            }
            bitmap = Bitmap.createScaledBitmap(bitmap, (int) (bitmap.getWidth() * resizeScale), (int) (bitmap.getHeight() * resizeScale), true);

            resizeBitmap.setBitmap(bitmap);
            return resizeBitmap;
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error occurred while resizing bitmap", e);
            return null;
        }
    }

    @Nullable
    public static ShortcutInfoCompat buildShortcutInfo(@NonNull Context context, @NonNull Originator originator, @Nullable Bitmap avatar, @NonNull Class<?> targetActivityClass, @Nullable Boolean incoming) {
        if (DEBUG) {
            Log.d(LOG_TAG, "buildShortcutInfo: context=" + context + " originator=" + originator + " avatar=" + avatar + " targetActivityClass=" + targetActivityClass + " incoming=" + incoming);
        }

        if (originator instanceof GroupMember) {
            originator = ((GroupMember) originator).getGroup();
        }

        // Be careful that the name can be changed by another thread.
        final String name = originator.getName();
        if (name == null || name.trim().isEmpty()) {
            // We use the name as the shortcut's short label, which is mandatory.
            return null;
        }

        // This intent is used when the user taps a shortcut after long-pressing the
        // app icon. At the moment it opens ConversationActivity.
        Intent intent = new Intent(context, targetActivityClass)
                .setType("*/*")
                .setAction(Intent.ACTION_SEND)
                .addCategory(CATEGORY_SHARE_TARGET);

        intent.putExtra(originator.isGroup() ? Intents.INTENT_GROUP_ID : Intents.INTENT_CONTACT_ID, originator.getId().toString());

        String shortcutId = originator.getShortcutId();

        IconCompat icon = null;
        if (avatar != null) {
            icon = bitmapToAdaptiveIcon(avatar);
        }

        Person person = new Person.Builder()
                .setKey(shortcutId)
                .setIcon(icon)
                .setName(name)
                .build();

        ShortcutInfoCompat.Builder builder = new ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(icon)
                .setLongLived(true)
                .setCategories(Set.of(CATEGORY_SHARE_TARGET)) // send the share intent to ShareActivity, see share_targets.xml
                .setIntent(intent)
                .setPerson(person)
                .setLocusId(new LocusIdCompat(shortcutId));

        List<String> capabilities;

        if (incoming == null) {
            // we're building the initial shortcut list so we want both capabilities
            capabilities = List.of("actions.intent.RECEIVE_MESSAGE", "actions.intent.SEND_MESSAGE");
        } else {
            // we're reacting to an event (message sent or received) so we only want the relevant capability.
            if (incoming) {
                capabilities = Collections.singletonList("actions.intent.RECEIVE_MESSAGE");
            } else {
                capabilities = Collections.singletonList("actions.intent.SEND_MESSAGE");
            }
        }

        for (String capability : capabilities) {
            if (originator.isGroup()) {
                builder.addCapabilityBinding(capability, "message.recipient.@type", Collections.singletonList("Audience"));
            } else {
                builder.addCapabilityBinding(capability);
            }
        }

        return builder.build();
    }

    @NonNull
    public static IconCompat bitmapToAdaptiveIcon(@NonNull Bitmap bitmap) {
        try {
            return IconCompat.createWithAdaptiveBitmap(CommonUtils.resizeAndCenterBitmap(bitmap));
        } catch (Exception e) {
            return IconCompat.createWithAdaptiveBitmap(bitmap);
        }
    }

    /**
     * Convert an arbitrary bitmap to comply with the
     * <a href="https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable">adaptative icon guidelines</a>
     *
     * @param sourceBitmap the bitmap to convert
     * @return a 108x108dp bitmap containing the sourceBitmap, centered and scaled to 72x72dp.
     */
    @NonNull
    private static Bitmap resizeAndCenterBitmap(@NonNull Bitmap sourceBitmap) {
        Bitmap bitmap = Bitmap.createBitmap(AdaptiveBitmapMetrics.outerSize, AdaptiveBitmapMetrics.outerSize, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(sourceBitmap, AdaptiveBitmapMetrics.innerSize, AdaptiveBitmapMetrics.innerSize, true);

        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(scaled, AdaptiveBitmapMetrics.padding, AdaptiveBitmapMetrics.padding, null);

        return bitmap;
    }

    private static class AdaptiveBitmapMetrics {

        private static final float ADAPTIVE_ICON_OUTER_SIZE = dpToPx(108f);
        private static final float ADAPTIVE_ICON_INNER_SIZE = dpToPx(72f);

        private static final int outerSize = (int) Math.ceil(ADAPTIVE_ICON_OUTER_SIZE);

        private static final int innerSize = (int) Math.ceil(ADAPTIVE_ICON_INNER_SIZE) +
                ((int) Math.floor(ADAPTIVE_ICON_OUTER_SIZE) < outerSize ? 2 : 0);

        private static final float padding = (outerSize - innerSize) / 2f;

        private AdaptiveBitmapMetrics() {
            // Not instantiable
        }

        private static float dpToPx(float dp) {
            return dp * Resources.getSystem().getDisplayMetrics().density;
        }
    }


    public static String formatInterval(int interval, String format) {

        if (format.equals("mm:ss") && interval > 3600) {
            format = "HH:mm:ss";
        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(format, Locale.getDefault());
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        return simpleDateFormat.format(new Date(interval * 1000L));
    }

    public static String formatItemInterval(Context context, long interval) {

        Date date = new Date(interval);
        Date now = new Date();

        long dateDiff = now.getTime() - date.getTime();
        long dayAgo = TimeUnit.MILLISECONDS.toDays(dateDiff);

        String formatDate = "";
        String dateToString = "";
        if (isYesterday(interval)) {
            dateToString = DateUtils.getRelativeTimeSpanString(date.getTime(), now.getTime(), DateUtils.DAY_IN_MILLIS).toString();
        } else if (dayAgo < 6 && !DateUtils.isToday(interval)) {
            formatDate = "EEEE";
        } else if (!DateUtils.isToday(interval)) {
            Calendar calendar1 = Calendar.getInstance();
            Calendar calendar2 = Calendar.getInstance();
            calendar1.setTime(new Date(System.currentTimeMillis()));
            calendar2.setTime(new Date(interval));

            long milliSeconds1 = calendar1.getTimeInMillis();
            long milliSeconds2 = calendar2.getTimeInMillis();
            long elapsedTime = (milliSeconds2 - milliSeconds1) / 1000;

            if (elapsedTime > ONE_YEAR) {
                formatDate = "EEE dd MMM yyyy";
            } else {
                formatDate = "EEE dd MMM";
            }
        }

        if (!formatDate.isEmpty()) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(formatDate, Locale.getDefault());
            dateToString = simpleDateFormat.format(date);
        }

        String formatTime;
        if (DateFormat.is24HourFormat(context)) {
            formatTime = "kk:mm";
        } else {
            formatTime = "hh:mm a";
        }

        SimpleDateFormat simpleTimeFormat = new SimpleDateFormat(formatTime, Locale.getDefault());
        String timeToString= simpleTimeFormat.format(date);

        if (dateToString.isEmpty()) {
            return timeToString;
        }
        return dateToString + " " + timeToString;
    }

    public static void setClipboard(Context context, String text) {

        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            ClipData clip = ClipData.newPlainText("Copied Text", text);
            clipboardManager.setPrimaryClip(clip);
        }
    }

    public static boolean isLayoutDirectionRTL() {

        return TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL;
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int orientation) {

        Matrix matrix = new Matrix();
        matrix.setRotate(orientation);

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static int getOrientation(Context context, Uri contentUri) {

        int orientation = ExifInterface.ORIENTATION_NORMAL;

        try (InputStream inputStream = context.getContentResolver().openInputStream(contentUri)) {
            if (inputStream != null) {
                ExifInterface exifInterface = new ExifInterface(inputStream);
                orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception exception) {
            if (Logger.ERROR) {
                Logger.error(LOG_TAG, "extracting orientation failed", exception);
            }
        }

        return orientation;
    }

    private static int getRotationDegrees(int orientation) {

        int rotation = 0;

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_180:
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                rotation = 180;
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
            case ExifInterface.ORIENTATION_ROTATE_90:
                rotation = 90;
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
            case ExifInterface.ORIENTATION_ROTATE_270:
                rotation = -90;
                break;
            default:
        }

        return rotation;
    }

    public static String formatTimeInterval(Context context, long timestamp) {

        Date date = new Date(timestamp);
        Date now = new Date();

        long dateDiff = now.getTime() - date.getTime();
        long dayAgo = TimeUnit.MILLISECONDS.toDays(dateDiff);

        String format = "dd/MM/yyyy";
        if (DateUtils.isToday(timestamp)) {
            if (DateFormat.is24HourFormat(context)) {
                format = "kk:mm";
            } else {
                format = "hh:mm a";
            }
        } else if (isYesterday(timestamp)) {
            return DateUtils.getRelativeTimeSpanString(date.getTime(), now.getTime(), DateUtils.DAY_IN_MILLIS).toString();
        } else if (dayAgo < 6) {
            format = "EEEE";
        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(format, Locale.getDefault());

        return simpleDateFormat.format(date);
    }

    public static String formatCallTimeInterval(Context context, long timestamp) {

        Date date = new Date(timestamp);
        Date now = new Date();

        long dateDiff = now.getTime() - date.getTime();
        long dayAgo = TimeUnit.MILLISECONDS.toDays(dateDiff);

        String formatDate = "";
        String dateToString = "";
        if (isYesterday(timestamp)) {
            dateToString = DateUtils.getRelativeTimeSpanString(date.getTime(), now.getTime(), DateUtils.DAY_IN_MILLIS).toString();
        } else if (dayAgo < 6 && !DateUtils.isToday(timestamp)) {
            formatDate = "EEEE";
        } else if (!DateUtils.isToday(timestamp)) {
            formatDate = "dd/MM/yyyy";
        }

        if (!formatDate.isEmpty()) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(formatDate, Locale.getDefault());
            dateToString = simpleDateFormat.format(date);
        }

        String formatTime;
        if (DateFormat.is24HourFormat(context)) {
            formatTime = "kk:mm";
        } else {
            formatTime = "hh:mm a";
        }

        SimpleDateFormat simpleTimeFormat = new SimpleDateFormat(formatTime, Locale.getDefault());
        String timeToString= simpleTimeFormat.format(date);

        if (dateToString.isEmpty()) {
            return timeToString;
        }
        return dateToString + "\n" + timeToString;
    }

    private static boolean isYesterday(long timestamp) {

        Calendar yesterday = Calendar.getInstance();
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(timestamp);
        yesterday.add(Calendar.DATE, -1);

        return yesterday.get(Calendar.YEAR) == date.get(Calendar.YEAR)
                && yesterday.get(Calendar.MONTH) == date.get(Calendar.MONTH)
                && yesterday.get(Calendar.DATE) == date.get(Calendar.DATE);
    }

    public static String capitalizeString(String string) {

        if (string == null || string.isEmpty()) {
            return string;
        }

        return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase();
    }

    public static URL extractURLFromString(String string) {

        if (string == null || string.isEmpty()) {
            return null;
        }

        Matcher matcher = Patterns.WEB_URL.matcher(string);
        while (matcher.find()) {
            String urlString = matcher.group();
            // Only accept https:// and http:// links since the pattern matches more links that are not really valid in our context.
            if (urlString.startsWith("https://") || urlString.startsWith("http://")) {
                try {
                    return new URL(urlString);
                } catch (MalformedURLException ignored) {
                }
            }
        }

        Matcher matcherIPV6 = IPV6_PATTERN.matcher(string);
        while (matcherIPV6.find()) {
            String urlString = matcherIPV6.group();
            // Only accept https:// and http:// links since the pattern matches more links that are not really valid in our context.
            if (urlString.startsWith("https://") || urlString.startsWith("http://")) {
                try {
                    return new URL(urlString);
                } catch (MalformedURLException ignored) {
                }
            }
        }

        return null;
    }

    public static String convertEmoji(String text) {

        if (text.contains(SMILEY_HAPPY)) {
            text = text.replace(SMILEY_HAPPY, EMOJI_HAPPY_CODE);
        }

        if (text.contains(SMILEY_SAD)) {
            text = text.replace(SMILEY_SAD, EMOJI_SAD_CODE);
        }

        return text;
    }

    @NonNull
    public static SpannableStringBuilder formatText(@NonNull String text, int searchSize) {

        final SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder(text);
        try {
            final List<Range> excludedRanges = new ArrayList<>();
            final Matcher urlMatcher = Patterns.WEB_URL.matcher(text);

            while (urlMatcher.find()) {
                Range range = new Range(urlMatcher.start(), urlMatcher.end());
                excludedRanges.add(range);
            }


            final List<Range> ranges = new ArrayList<>();

            final Matcher boldMatcher = BOLD_PATTERN.matcher(text);

            while (boldMatcher.find()) {
                int start = boldMatcher.start();
                int end = boldMatcher.end();
                if (end - start > 1) {
                    Range range = new Range(start, end);
                    if (!containsRange(range, excludedRanges)) {
                        ranges.add(range);
                    }
                }
            }

            final Matcher italicMatcher = ITALIC_PATTERN.matcher(text);

            while (italicMatcher.find()) {
                int start = italicMatcher.start();
                int end = italicMatcher.end();
                if (end - start > 1) {
                    Range range = new Range(start, end);
                    if (!containsRange(range, excludedRanges)) {
                        ranges.add(range);
                    }
                }
            }

            final Matcher strikeThroughMatcher = STRIKE_THROUGH_PATTERN.matcher(text);

            while (strikeThroughMatcher.find()) {
                int start = strikeThroughMatcher.start();
                int end = strikeThroughMatcher.end();
                if (end - start > 1) {
                    Range range = new Range(start, end);
                    if (!containsRange(range, excludedRanges)) {
                        ranges.add(range);
                    }
                }
            }

            Collections.sort(ranges, (r1, r2) -> r1.getStart() - r2.getStart());

            for (Range range : ranges) {
                String subString = text.substring(range.getStart(), range.getStart() + 1);
                String stringToReplace = text.substring(range.getStart(), range.getEnd());
                stringToReplace = stringToReplace.replace(subString, "");
                int offset = getOffset(range, ranges);
                range.setStart(range.getStart() - offset);
                range.setEnd(range.getEnd() - offset);
                spannableStringBuilder.replace(range.getStart(), range.getEnd(), stringToReplace);
                Range styleRange = new Range(range.getStart(), range.getEnd() - 2);

                switch (subString) {

                    case BOLD_STYLE_SYMBOL: {
                        StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
                        spannableStringBuilder.setSpan(styleSpan, styleRange.getStart(), styleRange.getEnd(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    }

                    case ITALIC_STYLE_SYMBOL: {
                        StyleSpan styleSpan = new StyleSpan(Typeface.ITALIC);
                        spannableStringBuilder.setSpan(styleSpan, styleRange.getStart(), styleRange.getEnd(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    }

                    case STRIKE_THROUGH_STYLE_SYMBOL:

                        if (searchSize != 0) {
                            StyleSpan styleSpan = new StyleSpan(Typeface.BOLD);
                            spannableStringBuilder.setSpan(styleSpan, styleRange.getStart(), styleRange.getEnd(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                            AbsoluteSizeSpan absoluteSizeSpan = new AbsoluteSizeSpan(searchSize);
                            spannableStringBuilder.setSpan(absoluteSizeSpan, styleRange.getStart(), styleRange.getEnd(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else {
                            StrikethroughSpan strikethroughSpan = new StrikethroughSpan();
                            spannableStringBuilder.setSpan(strikethroughSpan, styleRange.getStart(), styleRange.getEnd(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                }
            }
        } catch (Exception exception) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Ignored exception in formatText", exception);
            }
        }
        return spannableStringBuilder;
    }

    public static boolean isGooglePlayServicesAvailable(Context context) {

        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
    }

    private static int getOffset(Range range, List<Range> ranges) {

        int offset = 0;
        int endRange = range.getStart() + range.getLength();
        for (int i = 0; i < ranges.size(); i++) {
            Range r = ranges.get(i);
            long endRange2 = r.getStart() + r.getLength();
            if (range.getStart() == r.getStart()) {
                return offset;
            }

            if (range.getStart() > r.getStart()) {
                offset++;
            }

            if (endRange2 < endRange) {
                offset++;
            }
        }

        return offset;
    }

    private static boolean containsRange(Range range, List<Range> ranges) {

        for (int i = 0; i < ranges.size(); i++) {
            Range r = ranges.get(i);
            if (range.getStart() > r.getStart() && range.getStart() < r.getEnd()) {
                return true;
            }
        }

        return false;
    }
}
