/*
 *  Copyright (c) 2025 twinlife SA.
 *
 *  All Rights Reserved.
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.utils;

import android.util.Log;

import androidx.annotation.NonNull;

import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Space;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

public class BackupStats implements Serializable {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "BackupStats";

    public final int spaces;
    public final int contacts;
    public final int groups;
    public final int callReceivers;

    public BackupStats(@NonNull Map<UUID, Integer> stats) {
        int s = 0;
        int c = 0;
        int g = 0;
        int c2c = 0;

        for (Map.Entry<UUID, Integer> entry : stats.entrySet()) {
            if (entry.getKey().equals(Space.SCHEMA_ID)) {
                s = entry.getValue();
            } else if (entry.getKey().equals(Contact.SCHEMA_ID)) {
                c = entry.getValue();
            } else if (entry.getKey().equals(Group.SCHEMA_ID)) {
                g = entry.getValue();
            } else if (entry.getKey().equals(CallReceiver.SCHEMA_ID)) {
                c2c = entry.getValue();
            } else {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Unknown schema id " + entry.getKey());
                }
            }
        }

        spaces = s;
        contacts = c;
        groups = g;
        callReceivers = c2c;
    }

    @NonNull
    @Override
    public String toString() {
        return "BackupStats{" +
                "spaces=" + spaces +
                ", contacts=" + contacts +
                ", groups=" + groups +
                ", callReceivers=" + callReceivers +
                '}';
    }

}
