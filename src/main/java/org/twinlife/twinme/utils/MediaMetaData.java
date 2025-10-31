/*
 *  Copyright (c) 2015-2023 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Houssem Temanni (Houssem.Temanni@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.utils;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class MediaMetaData implements Parcelable {

    public enum Type {
        AUDIO, VIDEO
    }

    public final Type type;
    public final String artist;
    public final String album;
    public final String title;
    public Bitmap artwork;
    public final long duration;
    public final Uri artworkUri;

    public MediaMetaData(Type type, String artist, String album, String title, Bitmap artwork, long duration, Uri artworkUri) {

        this.type = type;
        this.artist = artist;
        this.album = album;
        this.title = title;
        this.artwork = artwork;
        this.duration = duration;
        this.artworkUri = artworkUri;
    }

    protected MediaMetaData(Parcel in) {
        if (in.readInt() == 1) {
            type = Type.VIDEO;
        } else {
            type = Type.AUDIO;
        }
        artist = in.readString();
        album = in.readString();
        title = in.readString();
        artwork = null;
        String uri = in.readString();
        if (uri != null) {
            artworkUri = Uri.parse(uri);
        } else {
            artworkUri = null;
        }

        duration = in.readLong();
    }

    public static final Creator<MediaMetaData> CREATOR = new Creator<MediaMetaData>() {
        @Override
        public MediaMetaData createFromParcel(Parcel in) {
            return new MediaMetaData(in);
        }

        @Override
        public MediaMetaData[] newArray(int size) {
            return new MediaMetaData[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(type == Type.VIDEO ? 1 : 0);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeString(title);
        if (artworkUri != null) {
            dest.writeString(artworkUri.toString());
        } else {
            dest.writeString(null);
        }
        dest.writeLong(duration);
    }
}
