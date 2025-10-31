/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.TelephonyManager;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;

import com.google.common.base.Strings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Telecom: buggy device handling taken from conversations.im (see src/main/java/eu/siacs/conversations/services/CallIntegration.java).
 */
public class FeatureUtils {

    /**
     * Samsung Galaxy Tab A claims to have FEATURE_CONNECTION_SERVICE but then throws
     * SecurityException when invoking placeCall(). Both Stock and LineageOS have this problem.
     *
     * <p>Lenovo Yoga Smart Tab YT-X705F claims to have FEATURE_CONNECTION_SERVICE but throws
     * SecurityException
     */
    private static final List<String> BROKEN_DEVICE_MODELS =
            Arrays.asList("gtaxlwifi", "a5y17lte", "YT-X705F", "HWAGS2");

    /**
     * all Realme devices at least up to and including Android 11 are broken
     *
     * <p>we are relatively sure that old Oppo devices are broken too. We get reports of 'number not
     * sent' from Oppo R15x (Android 10)
     *
     * <p>OnePlus 6 (Android 8.1-11) Device is buggy and always starts the OS call screen even
     * though we want to be self managed
     *
     * <p>a bunch of OnePlus devices are broken in other ways
     */
    private static final List<String> BROKEN_MANUFACTURES_UP_TO_11 =
            Arrays.asList("realme", "oppo", "oneplus");

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    public static boolean isTelecomSupported(@NonNull Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE // Android 14
                && hasSystemFeature(context)
                && isDeviceModelSupported();
    }

    private static boolean hasSystemFeature(@NonNull Context context) {
        final PackageManager packageManager = context.getPackageManager();

        // No mobile capability => most likely a tablet, with a poor Telecom implementation,
        // and getting a double-call is less likely so Telecom integration is not as useful.
        if (!hasMobileData(context)) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM);
        } else {
            //noinspection deprecation
            return packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
        }
    }

    private static boolean hasMobileData(@NonNull Context context) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return false;
        }

        TelephonyManager telMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        return telMgr != null && telMgr.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;

    }

    private static boolean isDeviceModelSupported() {
        final String manufacturer = Strings.nullToEmpty(Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        if (BROKEN_DEVICE_MODELS.contains(Build.DEVICE)) {
            return false;
        }
        if (BROKEN_MANUFACTURES_UP_TO_11.contains(manufacturer)
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            return false;
        }
        // we only know of one Umidigi device (BISON_GT2_5G) that doesn't work (audio is not being
        // routed properly) However with those devices being extremely rare it's impossible to gauge
        // how many might be effected and no Naomi Wu around to clarify with the company directly
        if ("umidigi".equals(manufacturer) && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            return false;
        }
        // SailfishOS's AppSupport do not support Call Integration
        if (Build.MODEL.endsWith("(AppSupport)")) {
            return false;
        }
        return true;
    }

}
