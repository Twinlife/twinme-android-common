/*
 *  Copyright (c) 2021-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 */

package org.twinlife.twinme.services;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.twinlife.twinlife.ConversationService;

import java.util.UUID;

class FileInfo {
    final Uri file;
    final String filename;
    final UUID sendTo;
    final ConversationService.DescriptorId replyTo;
    final ConversationService.Descriptor.Type type;
    final long expireTimeout;
    final boolean toDelete;
    final boolean allowCopy;

    FileInfo(Uri file, String filename, ConversationService.Descriptor.Type type, boolean toDelete, boolean allowCopy,
             @Nullable UUID sendTo, @Nullable ConversationService.DescriptorId replyTo, long expireTimeout) {
        this.file = file;
        this.filename = filename;
        this.type = type;
        this.toDelete = toDelete;
        this.allowCopy = allowCopy;
        this.sendTo = sendTo;
        this.replyTo = replyTo;
        this.expireTimeout = expireTimeout;
    }
}
