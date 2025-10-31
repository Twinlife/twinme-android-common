/*
 *  Copyright (c) 2022-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils.async;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.text.Html;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.utils.CommonUtils;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load the link metadata from the loader manager thread.
 */
public class LinkLoader<T> implements Loader<T> {
    private static final String LOG_TAG = "LinkLoader";
    private static final boolean DEBUG = false;

    public interface LinkObserver<T> {

        void onLinkMetadataLoaded(T item);
    }

    private static class LinkMetadataAsyncTask<T> extends AsyncTask<String, Void, String> {

        private static final Pattern OPEN_GRAPH_PATTERN = Pattern.compile("<\\s*meta[^>]*property\\s*=\\s*\"\\s*og:([^\"]+)\"[^>]*/?\\s*>");
        private static final Pattern CONTENT_PATTERN = Pattern.compile("content\\s*=\\s*\"([^\"]*)\"");
        private static final Pattern TITLE_PATTERN = Pattern.compile("<\\s*title[^>]*>(.*)<\\s*/title[^>]*>");
        private static final String METADATA_TITLE = "title";
        private static final String METADATA_IMAGE = "image";
        private static final int MAX_LINE_COUNT = 1000;  // Read a maximum of 1000 lines (<meta> and <title> are in the header).
        private static final int MAX_LENGTH = 32 * 1024; // Read a maximum of 32K

        private final LinkLoader<T> mLinkLoader;
        private final URL mURL;
        private String mHtml;

        public LinkMetadataAsyncTask(LinkLoader<T> linkLoader, @NonNull URL url) {

            mURL = url;
            mLinkLoader = linkLoader;
        }

        /**
         * Check that the content type refers to an HTML content.
         *
         * @param contentType the content type or null.
         * @return true if this is an HTML content.
         */
        private static boolean isHtml(@Nullable String contentType) {

            if (contentType == null) {
                return false;
            }

            // Content type can be upper or mixed case and it can contain a charset (ex: text/html; charset=utf-8)
            contentType = contentType.toLowerCase(Locale.ROOT);
            int sep = contentType.indexOf(';');
            if (sep > 0) {
                contentType = contentType.substring(0, sep);
            }

            return "text/html".equals(contentType);
        }

        @Override
        protected String doInBackground(String... params) {

            HttpURLConnection urlConnection = null;
            try {
                urlConnection = (HttpURLConnection) mURL.openConnection();
                int code = urlConnection.getResponseCode();

                if (code == HttpURLConnection.HTTP_OK && isHtml(urlConnection.getContentType())) {
                    StringBuilder result = new StringBuilder();
                    try (InputStream inputStream = new BufferedInputStream(urlConnection.getInputStream())) {
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                        String line;
                        int count = 0;
                        while ((line = bufferedReader.readLine()) != null && count < MAX_LINE_COUNT && result.length() < MAX_LENGTH) {
                            result.append(line);
                            count++;
                        }
                    }
                    return result.toString();
                }
                return null;

            } catch (Throwable exception) {
                if (DEBUG) {
                    Log.e(LOG_TAG, "Exception", exception);
                }
                return null;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(String result) {

            mHtml = result;
            parseHtml();
        }

        private void parseHtml() {

            if (mHtml == null) {
                mLinkLoader.onLinkMetadataLoaded();
            } else {
                Matcher matcher = OPEN_GRAPH_PATTERN.matcher(mHtml);
                String title = null;
                String image = null;
                while (matcher.find()) {
                    String tag = matcher.group();
                    String property = null;
                    if (matcher.groupCount() > 0) {
                        property = matcher.group(1);
                    }

                    if (property != null) {
                        if (property.equals(METADATA_TITLE)) {
                            Matcher titleMatcher = CONTENT_PATTERN.matcher(tag);
                            if (titleMatcher.find() && titleMatcher.groupCount() > 0) {
                                if (titleMatcher.group(1) != null) {
                                    title = Html.fromHtml(titleMatcher.group(1)).toString();
                                }
                            }
                        } else if (property.equals(METADATA_IMAGE)) {
                            Matcher imageMatcher = CONTENT_PATTERN.matcher(tag);
                            if (imageMatcher.find() && imageMatcher.groupCount() > 0) {
                                if (imageMatcher.group(1) != null) {
                                    image = imageMatcher.group(1);
                                }
                            }
                        }
                    }
                }

                if (title != null) {
                    mLinkLoader.setTitle(title);
                } else {
                    Matcher titleMatcher = TITLE_PATTERN.matcher(mHtml);
                    if (titleMatcher.find() && titleMatcher.groupCount() > 0) {
                        title = Html.fromHtml(titleMatcher.group(1)).toString();
                        mLinkLoader.setTitle(title);
                    }
                }

                if (image != null) {
                    mLinkLoader.loadImage(image);
                    return;
                }

                mLinkLoader.onLinkMetadataLoaded();
            }
        }
    }

    private static class DownloadImageTask<T> extends AsyncTask<String, Void, Bitmap> {

        private final LinkLoader<T> mLinkLoader;

        public DownloadImageTask(LinkLoader<T> linkLoader) {
            mLinkLoader = linkLoader;
        }

        protected Bitmap doInBackground(String... urls) {
            Bitmap bitmap = null;
            try (InputStream in = new URL(urls[0]).openStream()) {
                bitmap = BitmapFactory.decodeStream(in);
            } catch (Throwable exception) {
                Log.e(LOG_TAG, "Exception", exception);
            }
            return bitmap;
        }

        protected void onPostExecute(Bitmap bitmap) {

            if (bitmap != null) {
                mLinkLoader.setImage(bitmap);
            } else {
                mLinkLoader.onLinkMetadataLoaded();
            }
        }
    }

    @NonNull
    private final T mItem;
    @Nullable
    private volatile ConversationService.ObjectDescriptor mObjectDescriptor;
    @Nullable
    private volatile Bitmap mImage;
    @Nullable
    private volatile String mTitle;
    @Nullable
    private LinkObserver<T> mObserver;

    private volatile boolean mIsFinished;

    /**
     * Create the link loader instance.
     *
     * @param item             the item to redraw when the link is loaded.
     * @param objectDescriptor the image descriptor to load.
     */
    public LinkLoader(@NonNull T item, @NonNull ConversationService.ObjectDescriptor objectDescriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "LinkLoader objectDescriptor=" + objectDescriptor);
        }

        mItem = item;
        mObjectDescriptor = objectDescriptor;
        mIsFinished = false;
    }

    public void setLinkObserver(LinkObserver<T> linkObserver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setLinkObserver");
        }

        mObserver = linkObserver;
    }

    /**
     * Cancel loading the image thumbnail.
     */
    public void cancel() {
        if (DEBUG) {
            Log.d(LOG_TAG, "cancel");
        }

        mObjectDescriptor = null;
    }

    /**
     * Get the image thumbnail if it was loaded.
     *
     * @return the image thumbnail or null if it was not yet loaded.
     */
    @Nullable
    public Bitmap getImage() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getImage");
        }

        return mImage;
    }

    public void loadImage(String imageUrl) {
        if (DEBUG) {
            Log.d(LOG_TAG, "loadImage: " + imageUrl);
        }

        DownloadImageTask<T> downloadImageTask = new DownloadImageTask<>(this);
        downloadImageTask.execute(imageUrl);
    }

    public void setImage(Bitmap bitmap) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setImage: " + bitmap);
        }

        mImage = bitmap;
        if (mObserver != null) {
            mObserver.onLinkMetadataLoaded(mItem);
        }
    }

    public ConversationService.ObjectDescriptor getObjectDescriptor() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getObjectDescriptor: ");
        }

        return mObjectDescriptor;
    }

    /**
     * Get the link title if it was loaded.
     *
     * @return the title or null if it was not yet loaded.
     */
    @Nullable
    public String getTitle() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getTitle");
        }

        return mTitle;
    }

    public void setTitle(String title) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setTitle: " + title);
        }

        mTitle = title;
    }

    /**
     * Check if this loader was finished.
     *
     * @return true if this loader has finished.
     */
    public boolean isFinished() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isFinished");
        }

        return mIsFinished;
    }

    /**
     * Load or try to load the image thumbnail to get an image to display.
     *
     * @param context       the context.
     * @param twinmeContext the twinme context.
     * @return the object to redraw when the image was loaded or null if there is no change.
     */
    @Nullable
    public T loadObject(@NonNull Context context, @NonNull TwinmeContext twinmeContext) {
        if (DEBUG) {
            Log.d(LOG_TAG, "loadObject");
        }

        if (mTitle != null && mImage != null) {
            mIsFinished = true;
            return null;
        }

        final ConversationService.ObjectDescriptor objectDescriptor = mObjectDescriptor;
        if (objectDescriptor == null) {
            mIsFinished = true;
            return null;
        }

        URL url = CommonUtils.extractURLFromString(objectDescriptor.getMessage());
        if (url != null) {
            final String link = url.toString();
            if (link.startsWith("http:")) {
                try {
                    url = new URL(link.replace("http:", "https:"));
                } catch (MalformedURLException exception) {
                    if (DEBUG) {
                        Log.e(LOG_TAG, "Exception", exception);
                    }
                    mIsFinished = true;
                    return null;
                }
            } else if (!link.startsWith("http")) {
                try {
                    url = new URL("https://" + url);
                } catch (MalformedURLException exception) {
                    if (DEBUG) {
                        Log.e(LOG_TAG, "Exception", exception);
                    }
                    mIsFinished = true;
                    return null;
                }
            }
            LinkMetadataAsyncTask<T> linkLoaderAsyncTask = new LinkMetadataAsyncTask<>(this, url);
            linkLoaderAsyncTask.execute();
        }

        return null;
    }

    private void onLinkMetadataLoaded() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onLinkMetadataLoaded");
        }

        if (mObserver != null) {
            mObserver.onLinkMetadataLoaded(mItem);
        }
    }
}