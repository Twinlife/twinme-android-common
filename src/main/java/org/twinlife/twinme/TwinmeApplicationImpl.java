/*
 *  Copyright (c) 2016-2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.AndroidConfigurationServiceImpl;
import org.twinlife.twinlife.ConnectionStatus;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.TwinlifeContextImpl;
import org.twinlife.twinlife.TwinlifeServiceConnectionImpl;
import org.twinlife.twinlife.job.AndroidJobServiceImpl;
import org.twinlife.twinlife.job.SchedulerJobServiceImpl;
import org.twinlife.twinlife.util.Logger;
import org.twinlife.twinme.ui.TwinmeApplication;

import java.io.InputStream;
import java.lang.ref.WeakReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public abstract class TwinmeApplicationImpl extends Application implements TwinmeApplication {
    private static final String LOG_TAG = "TwinmeApplicationImpl";
    private static final boolean DEBUG = false;
    private static final int IV_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BYTES = 32;

    protected static WeakReference<TwinmeApplicationImpl> sInstance;

    private volatile boolean mRunning = false;
    private TwinmeConfiguration mTwinmeConfiguration;
    @Nullable
    private TwinmeContext mTwinmeContext;
    private AndroidJobServiceImpl mJobService;
    private TwinlifeServiceConnectionImpl mTwinlifeServiceConnectionImpl;

    //
    // Override Application methods
    //

    /**
     * Get the TwinmeApplication instance from the context.
     * <p>
     * We have found that the Activity.getApplication() sometimes does not return the expected TwinmeApplication instance.
     * We are having ClassCastException when we try to convert it to a TwinmeApplication class despite a correct
     * setup in the Android manifest.
     *
     * @param context the context.
     * @return the Twinme application instance.
     */
    public static TwinmeApplicationImpl getInstance(@Nullable Context context) {

        for (int retry = 0; retry < 10; retry++) {
            if (context instanceof TwinmeApplicationImpl) {
                return (TwinmeApplicationImpl) context;
            }

            if (TwinmeApplicationImpl.sInstance != null) {
                TwinmeApplicationImpl app = TwinmeApplicationImpl.sInstance.get();
                if (app != null) {
                    return app;
                }
            }

            if (context != null) {
                Context appContext = context.getApplicationContext();
                if (appContext instanceof TwinmeApplicationImpl) {
                    return (TwinmeApplicationImpl) appContext;
                }
            }

            // Try to wait to let Android initialize the application (not sure it will help...).
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {

            }
        }
        return null;
    }

    /**
     * Initialize the application by creating the twinme context and twinlife services.
     * Schedule the `setup` method with the twinme context from the twinlife executor's thread.
     * It is executed BEFORE the twinlife library is configured.  This allows to do some specific
     * setup before the twinlife library is configured and without blocking the current thread.
     * @param setup the setup to execute with the twinme context.
     */
    public void initialize(@NonNull TwinmeContext.Consumer<TwinmeContext> setup) {
        if (DEBUG) {
            Log.d(LOG_TAG, "initialize");
        }

        final AndroidConfigurationServiceImpl configurationService = new AndroidConfigurationServiceImpl(this);

        mRunning = true;
        mJobService = new SchedulerJobServiceImpl(this);

        mTwinmeContext = new TwinmeContextImpl(this, mTwinmeConfiguration, mJobService, configurationService);
        mJobService.setTwinlifeContext(mTwinmeContext);

        mTwinmeContext.execute(() -> setup.accept(mTwinmeContext));

        mTwinlifeServiceConnectionImpl = new TwinlifeServiceConnectionImpl((TwinlifeContextImpl) mTwinmeContext, this, configurationService);
        mTwinlifeServiceConnectionImpl.start();
    }

    //
    // Implement TwinmeApplication Methods
    //

    @NonNull
    public Application getApplication() {

        return this;
    }


    @Override
    @Nullable
    public TwinmeContext getTwinmeContext() {

        return mTwinmeContext;
    }

    @Override
    public boolean isRunning() {

        return mRunning;
    }

    @Override
    public void setNotRunning() {

        mRunning = false;
    }

    @Override
    @NonNull
    public ConnectionStatus getConnectionStatus() {

        if (mTwinmeContext == null) {
            return ConnectionStatus.NO_SERVICE;
        } else {
            return mTwinmeContext.getConnectionStatus();
        }
    }

    @Override
    public void stop() {

        mRunning = false;

        mTwinlifeServiceConnectionImpl.stop();
    }

    @Override
    public void restart() {

        if (mRunning) {

            return;
        }
        mRunning = true;
    }

    /**
     * Allocate a network lock to try keeping the service alive.
     * <p>
     * When the network lock is not needed anymore, its `release` operation must be called.
     *
     * @return the network lock instance.
     */
    @NonNull
    public JobService.NetworkLock allocateNetworkLock() {

        return mJobService.allocateNetworkLock();
    }

    /**
     * Allocate a VoIP lock to tell the service a VoIP call is in progress and we must not disconnect
     * while we are in background.
     * @return the VoIP lock instance.
     */
    @NonNull
    public JobService.VoIPLock allocateVoIPLock() {

        return mJobService.allocateVoIPLock();
    }

    /**
     * Allocate a processing lock to tell the system we need the CPU.
     * <p>
     * When the processing lock is not needed anymore, its `release` operation must be called.
     *
     * @return the processing lock instance.
     */
    @NonNull
    public JobService.ProcessingLock allocateProcessingLock() {

        return mJobService.allocateProcessingLock();
    }

    /**
     * Allocate an interactive lock to tell activate the screen and tell the system we need the CPU.
     * <p>
     * When the interactive lock is not needed anymore, its `release` operation must be called.
     *
     * @return the interactive lock instance.
     */
    @NonNull
    public JobService.InteractiveLock allocateInteractiveLock() {

        return mJobService.allocateInteractiveLock();
    }

    /**
     * Check if the feature is enabled for the application.
     *
     * @param feature the feature identification.
     * @return true if the feature is enabled.
     */
    @Override
    public boolean isFeatureSubscribed(@NonNull Feature feature) {

        if (feature == Feature.GROUP_CALL && mTwinmeContext != null) {
            return mTwinmeContext.getAccountService().isFeatureSubscribed("group-call");

        } else {
            return false;
        }
    }

    //
    // Protected Methods
    //

    protected final void setTwinlifeConfiguration(@NonNull TwinmeConfiguration twinmeConfiguration, @Nullable InputStream configStream) {

        if (mTwinmeConfiguration == null) {
            mTwinmeConfiguration = twinmeConfiguration;
            try {
                // Deobfuscate and read the app configuration.
                if (configStream != null) {
                    byte[] data = new byte[4096];
                    int length = configStream.read(data);

                    SecretKeySpec securedKey;
                    long seed = twinmeConfiguration.getSerial();
                    byte[] keystream = new byte[length];
                    for (int i = 0; i < 48; i++) {
                        keystream[i] = (byte) seed;
                        seed = seed * 31 + (data[i] ^ keystream[i]);
                    }
                    for (int i = 48; i < keystream.length; i++) {
                        seed = (seed * 1103515245 + 12345) & 0x7fffffff;
                        keystream[i] = (byte) seed;
                    }

                    for (int i = 0; i < length; i++) {
                        data[i] = (byte) (data[i] ^ keystream[i]);
                    }
                    byte[] key = new byte[KEY_LENGTH_BYTES];
                    System.arraycopy(data, 0, key, 0, KEY_LENGTH_BYTES);
                    securedKey = new SecretKeySpec(key, "AES");

                    final Cipher cipher;
                    final byte[] iv = new byte[IV_LENGTH_BYTES];
                    System.arraycopy(data, key.length, iv, 0, IV_LENGTH_BYTES);

                    final byte[] mode = new byte[data[iv.length + key.length]];
                    System.arraycopy(data, iv.length + key.length + 1, mode, 0, mode.length);

                    cipher = Cipher.getInstance(new String(mode));
                    cipher.init(Cipher.DECRYPT_MODE, securedKey, new IvParameterSpec(iv));

                    final byte[] encryptedData = new byte[length - iv.length - key.length - 1 - mode.length];
                    System.arraycopy(data, iv.length + key.length + 1 + mode.length, encryptedData, 0, encryptedData.length);
                    data = cipher.doFinal(encryptedData);

                    if (data != null) {
                        twinmeConfiguration.read(data);
                    }
                }
            } catch (Exception ex) {
                // Don't crash, don't report a log, the user will see the Fatal error view with a message.
                if (Logger.DEBUG) {
                    Log.e(LOG_TAG, "Error:", ex);
                }
            }
        }
    }
}
