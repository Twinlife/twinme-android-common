/*
 *  Copyright (c) 2024-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils.update;

import android.content.Context;

import java.util.List;

public interface LastVersion {
    boolean isValid();

    boolean isCurrentVersion();

    boolean isMajorVersion();

    boolean isMinorVersion();

    boolean hasNewVersion();

    boolean needUpdate();

    boolean isMajorVersionWithUpdate(boolean update);

    boolean isVersionUpdated();

    String getVersionNumber();

    void setVersionNumber(String versionNumber);

    void setMinSupportedSDK(String minSupportedSDK);

    List<String> getImages();

    List<String> getImagesDark();

    void setImages(List<String> images);

    void setImagesDark(List<String> images);

    void setMinorChanges(List<String> minorChanges);

    String getMinorChanges();

    List<String>  getListMinorChanges();

    void setMajorChanges(List<String> majorChanges);

    String getMajorChanges();

    List<String>  getListMajorChanges();

    void save(Context ctx);
}
