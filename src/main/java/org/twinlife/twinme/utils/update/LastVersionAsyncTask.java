/*
 *  Copyright (c) 2022-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.update;

import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LastVersionAsyncTask extends AsyncTask<String, String, String> {

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
    @NonNull
    private final WeakReference<Context> mContext;
    private final String mUrl;

    public LastVersionAsyncTask(@NonNull Context context, @NonNull LastVersion lastVersion, @NonNull String url) {

        mContext = new WeakReference<>(context);
        mLastVersion = lastVersion;
        mUrl = url;
    }

    @Override
    protected String doInBackground(String... strings) {

        URL url;
        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            url = new URL(mUrl);
            connection = (HttpURLConnection) url.openConnection();
            Locale locale = Locale.getDefault();
            String language = locale.toString().replace('_', '-');
            connection.setRequestProperty("Accept-Language", language);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.connect();
            try (InputStream stream = connection.getInputStream()) {

                reader = new BufferedReader(new InputStreamReader(stream));

                StringBuilder buffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line);
                    buffer.append("\n");
                }

                return buffer.toString();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    protected void onPostExecute(String result) {
        super.onPostExecute(result);
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
                Context ctx = mContext.get();
                if (ctx != null) {
                    mLastVersion.save(ctx);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
    }
}