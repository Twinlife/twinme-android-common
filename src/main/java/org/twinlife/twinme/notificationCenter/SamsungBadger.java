/*
 *  Copyright (c) 2017-2022 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.notificationCenter;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.twinlife.twinme.utils.FileInfo;

class SamsungBadger extends Badger {
    private static final String LOG_TAG = "SamsungBadger";
    private static final boolean DEBUG = false;

    private static final String CONTENT_URI = "content://com.sec.badge/apps?notify=true";
    private static final String[] CONTENT_PROJECTION = new String[]{"_id", "class"};

    SamsungBadger(Context context, ComponentName componentName) {

        super(context, componentName);
    }

    @Override
    protected void updateBadgeNumber(int badgeNumber) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateBadgeNumber badgeNumber=" + badgeNumber);
        }

        Uri mUri = Uri.parse(CONTENT_URI);
        ContentResolver contentResolver = mContext.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = contentResolver.query(mUri, CONTENT_PROJECTION, "package=?", new String[]{
                    mComponentName.getPackageName()}, null);
            if (cursor != null) {
                String entryActivityName = mComponentName.getClassName();
                boolean entryActivityExist = false;
                while (cursor.moveToNext()) {
                    int id = cursor.getInt(0);
                    ContentValues contentValues = getContentValues(mComponentName, badgeNumber, false);
                    contentResolver.update(mUri, contentValues, "_id=?", new String[]{String.valueOf(id)});
                    if (entryActivityName.equals(FileInfo.getColumnString(cursor, "class"))) {
                        entryActivityExist = true;
                    }
                }

                if (!entryActivityExist) {
                    ContentValues contentValues = getContentValues(mComponentName, badgeNumber, true);
                    contentResolver.insert(mUri, contentValues);
                }
            }
        } catch (Exception exception) {
            Log.e("LOG_TAG", "setBadgeNumber exception=" + exception);
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
        }
    }

    private ContentValues getContentValues(ComponentName componentName, int badgeCount, boolean isInsert) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContentValues componentName=" + componentName + " badgeCount=" + badgeCount + " isInsert=" + isInsert);
        }

        ContentValues contentValues = new ContentValues();
        if (isInsert) {
            contentValues.put("package", componentName.getPackageName());
            contentValues.put("class", componentName.getClassName());
        }

        contentValues.put("badgecount", badgeCount);

        return contentValues;
    }
}
