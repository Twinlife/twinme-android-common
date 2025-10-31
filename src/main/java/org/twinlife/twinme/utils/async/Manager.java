/*
 *  Copyright (c) 2021 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.utils.async;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.TwinmeContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous object loader, to use this:
 * <p>
 * 1. Implement the LoaderListener interface on the Activity,
 * <p>
 * 2. Allocate an instance of Manager in the onCreate() or onResume(),
 * <p>
 * 3. For async loading, allocate a ImageLoader, VideoLoader, or XXXLoader,
 * <p>
 * 4. Add the XXXLoader instance to the manager through the addItem(Loader) instance,
 * <p>
 * 5. In onDestroy(), stop the manager by calling the stop() method.
 */
public class Manager<T> {
    private static final String LOG_TAG = "Manager";
    private static final boolean DEBUG = false;

    @NonNull
    private final Context mContext;
    @NonNull
    private final TwinmeContext mTwinmeContext;
    @NonNull
    private final ScheduledExecutorService mExecutor;
    @NonNull
    private final List<Loader<T>> mItems = new ArrayList<>();
    @NonNull
    private final Handler mHandler = new Handler();
    @NonNull
    private final LoaderListener<T> mOnLoaded;
    @Nullable
    private List<T> mLoaded = null;
    private boolean mScheduled;
    private boolean mNotified;

    /**
     * Create the asynchronous object loader.
     *
     * @param context       the context.
     * @param twinmeContext the twinme context.
     * @param onLoaded      the listener that is called when objects are loaded.
     */
    public Manager(@NonNull Context context, @NonNull TwinmeContext twinmeContext, @NonNull LoaderListener<T> onLoaded) {
        if (DEBUG) {
            Log.d(LOG_TAG, "Manager");
        }

        mContext = context;
        mTwinmeContext = twinmeContext;
        mOnLoaded = onLoaded;
        mExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Stop the async loader (should be called from onDestroy).
     */
    public void stop() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stop");
        }

        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdown();
    }

    /**
     * Clear the list of items to be loaded (should be called from onPause).
     */
    public void clear() {
        if (DEBUG) {
            Log.d(LOG_TAG, "clear");
        }

        synchronized (this) {
            mItems.clear();
            mLoaded = null;
        }
    }

    /**
     * Add an item to be loaded by the background executor.
     *
     * @param item the new item to load.
     */
    public void addItem(@NonNull final Loader<T> item) {
        if (DEBUG) {
            Log.d(LOG_TAG, "addItem");
        }

        synchronized (this) {
            mItems.add(item);
            if (!mScheduled) {
                mScheduled = true;
                mExecutor.schedule(this::loadItems, 10, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Notify the UI that some loadable items have been refreshed.
     * <p>
     * This method is called from the main UI thread.
     */
    private void refreshItems() {
        if (DEBUG) {
            Log.d(LOG_TAG, "refreshItems");
        }

        List<T> loaded;
        synchronized (this) {
            loaded = mLoaded;
            mLoaded = null;
            mNotified = false;
        }

        if (loaded != null) {
            mOnLoaded.onLoaded(loaded);
        }
    }

    /**
     * Load the item data (image, audio track, ...) from the background executor thread.
     */
    private void loadItems() {
        if (DEBUG) {
            Log.d(LOG_TAG, "loadItems");
        }

        while (true) {
            // Pick a loader to load or terminate.
            final Loader<T> loader;
            synchronized (this) {
                if (mItems.isEmpty()) {
                    mScheduled = false;

                    return;
                }
                loader = mItems.remove(0);
            }

            try {
                T item = loader.loadObject(mContext, mTwinmeContext);
                if (item != null) {
                    // The loader has loaded an object, schedule a UI refresh.
                    synchronized (this) {
                        if (mLoaded == null) {
                            mLoaded = new ArrayList<>();
                        }
                        mLoaded.add(item);
                        if (!mNotified) {
                            mNotified = true;
                            mHandler.post(this::refreshItems);
                        }
                    }
                } else if (loader instanceof LinkLoader) {
                    LinkLoader.LinkObserver<T> linkObserver = itemLoaded -> {
                        if (itemLoaded != null) {
                            asyncResfresh(itemLoaded);
                        }
                    };

                    LinkLoader<T> linkLoader = (LinkLoader<T>) loader;
                    linkLoader.setLinkObserver(linkObserver);
                }
            } catch (Exception ex) {
                if (Logger.ERROR) {
                    Log.e(LOG_TAG, "Exception ", ex);
                }
            }
        }
    }

    private void asyncResfresh (T item) {
        if (DEBUG) {
            Log.d(LOG_TAG, "asyncResfresh: " + item);
        }

        synchronized (this) {
            if (mLoaded == null) {
                mLoaded = new ArrayList<>();
            }
            mLoaded.add(item);
            if (!mNotified) {
                mNotified = true;
                mHandler.post(this::refreshItems);
            }
        }
    }
}
