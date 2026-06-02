/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.faq;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.actions.BackgroundAction;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class FAQAction extends BackgroundAction {

    private static final String LOG_TAG = "FAQAction";
    private static final boolean DEBUG = false;

    private static final int CONNECT_TIMEOUT = 30 * 1000;

    private final WeakReference<TwinmeActivity> mActivityWeakReference;

    @NonNull
    private final File mCacheDir;
    private final FAQ mFaq;
    private final String mUrl;

    public FAQAction(@NonNull TwinmeActivity activity, @NonNull FAQ faq, @Nullable String url) {
        super(activity.getTwinmeContext(), CONNECT_TIMEOUT);
        if (DEBUG) {
            Log.d(LOG_TAG, "FAQAction twinmeContext=" + activity.getTwinmeContext());
        }

        mActivityWeakReference = new WeakReference<>(activity);
        mCacheDir = activity.getTwinmeContext().getCacheDir();
        mUrl = url;
        mFaq = faq;
    }

    @Override
    protected void execute() {
        if (DEBUG) {
            Log.d(LOG_TAG, "execute");
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

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (InputStream stream = connection.getInputStream()) {

                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                    StringBuilder buffer = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        buffer.append(line);
                        buffer.append("\n");
                    }

                    saveFAQ(buffer.toString());
                }
            }
        } catch (Exception e) {
            if (Logger.ERROR) {
                Log.e(LOG_TAG, "execute Exception", e);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        final TwinmeActivity activity = mActivityWeakReference.get();
        if (activity != null) {
            activity.onExecutionSuccess();
        }

        EventMonitor.event("execute", mStartTime);
    }

    private void saveFAQ(@Nullable String result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "saveFAQ " + result);
        }

        if (result != null) {
            mFaq.setJson(result);
            mFaq.save(mCacheDir);
        }
    }
}
