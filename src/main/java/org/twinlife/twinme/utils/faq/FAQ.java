/*
 *  Copyright (c) 2026 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils.faq;

import android.content.Context;
import androidx.annotation.NonNull;
import java.io.File;
import java.util.List;

public interface FAQ {

    List<UIFAQCategory> getCategories(Context context);

    void setJson(String json);

    void save(@NonNull File cacheDir);
}

