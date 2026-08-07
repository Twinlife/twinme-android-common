/*
 *  Copyright (c) 2022-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils.update;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.actions.BackgroundAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LastVersionAction extends BackgroundAction {
    private static final String LOG_TAG = "LastVersionAction";
    private static final boolean DEBUG = false;

    // public static final String LAST_VERSION_URL = BuildConfig.CHECK_VERSION_URL;
    public static final String VERSION_KEY = "version";
    public static final String MIN_SDK_KEY = "minSdk";
    public static final String IMAGES_KEY = "images";
    public static final String IMAGES_DARK_KEY = "images_dark";
    public static final String CHANGES_KEY = "changes";
    public static final String MAJOR_KEY = "major";
    public static final String MINOR_KEY = "minor";
    private static final int CONNECT_TIMEOUT = 30 * 1000; // 30s

    @NonNull
    private final LastVersion mLastVersion;
    private final String mUrl;
    @NonNull
    private final File mCacheDir;

    public LastVersionAction(@NonNull TwinmeContext twinmeContext, @NonNull LastVersion lastVersion, @NonNull String url) {
        super(twinmeContext, CONNECT_TIMEOUT);
        if (DEBUG) {
            Log.d(LOG_TAG, "LastVersionAction twinmeContext=" + twinmeContext + " lastVersion=" + lastVersion + " url=" + url);
        }

        mCacheDir = twinmeContext.getCacheDir();
        mLastVersion = lastVersion;
        mUrl = url;
    }

    @Override
    protected void execute() {
        if (DEBUG) {
            Log.d(LOG_TAG, "fetchVersion " + mUrl);
        }

        URL url;
        HttpURLConnection connection = null;

        try {
            url = new URL(mUrl);
            connection = (HttpURLConnection) url.openConnection();
            Locale locale = Locale.getDefault();
            String language = locale.toString().replace('_', '-');
            connection.setRequestProperty("Accept-Language", language);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.connect();
            try (InputStream stream = connection.getInputStream()) {

                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder buffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line);
                    buffer.append("\n");
                }

                saveVersion(buffer.toString());
            }
        } catch (Exception e) {
            if (Logger.ERROR) {
                Log.e(LOG_TAG, "fetchVersion Exception", e);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        EventMonitor.event("fetchVersion", mStartTime);
    }

    private void saveVersion(@Nullable String result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "saveVersion " + result);
        }

        if (result != null) {
            try {
                JSONObject jsonObject = new JSONObject(result);
                if (jsonObject.has(VERSION_KEY)) {
                    mLastVersion.setVersionNumber(jsonObject.getString(VERSION_KEY));
                }

                if (jsonObject.has(MIN_SDK_KEY)) {
                    mLastVersion.setMinSupportedSDK(jsonObject.getString(MIN_SDK_KEY));
                }

                if (jsonObject.has(IMAGES_KEY)) {
                    JSONArray imagesArray = jsonObject.getJSONArray(IMAGES_KEY);
                    List<String> images = new ArrayList<>();
                    for (int i = 0; i < imagesArray.length(); i++){
                        images.add(imagesArray.getString(i));
                    }
                    mLastVersion.setImages(images);
                }

                if (jsonObject.has(IMAGES_DARK_KEY)) {
                    JSONArray imagesDarkArray = jsonObject.getJSONArray(IMAGES_DARK_KEY);
                    List<String> imagesDark = new ArrayList<>();
                    for (int i = 0; i < imagesDarkArray.length(); i++){
                        imagesDark.add(imagesDarkArray.getString(i));
                    }
                    mLastVersion.setImagesDark(imagesDark);
                }

                if (jsonObject.has(CHANGES_KEY)) {
                    JSONObject changesObject = jsonObject.getJSONObject(CHANGES_KEY);
                    JSONArray minorArray = changesObject.getJSONArray(MINOR_KEY);
                    List<String> minorChanges = new ArrayList<>();
                    for (int i = 0; i < minorArray.length(); i++){
                        minorChanges.add(minorArray.getString(i));
                    }
                    mLastVersion.setMinorChanges(minorChanges);

                    JSONArray majorArray = changesObject.getJSONArray(MAJOR_KEY);
                    List<String> majorChanges = new ArrayList<>();
                    for (int i = 0; i < majorArray.length(); i++){
                        majorChanges.add(majorArray.getString(i));
                    }
                    mLastVersion.setMajorChanges(majorChanges);
                }

                // Save the information in a cache file when everything is loaded.
                mLastVersion.save(mCacheDir);

            } catch (JSONException e) {
                if (Logger.ERROR) {
                    Log.e(LOG_TAG, "Exception when parsing JSON: ", e);
                }
            }
        }
    }
}