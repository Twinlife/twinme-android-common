/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.glide;

import com.bumptech.glide.load.Option;
import com.bumptech.glide.request.RequestOptions;

public class Modes {

    static final Option<Boolean> THUMBNAIL =
            Option.memory("org.twinlife.twinme.glide.thumbnail", false);

    // Option to enable thumbnail for ImageDescriptor (default is normal image).
    public static final RequestOptions AS_THUMBNAIL = RequestOptions.option(THUMBNAIL, true);

    static final Option<Boolean> NORMAL =
            Option.memory("org.twinlife.twinme.glide.avatar", false);

    // Option to enable normal image for avatar (default is thumbnail).
    public static final RequestOptions AS_NORMAL = RequestOptions.option(NORMAL, true);
}
