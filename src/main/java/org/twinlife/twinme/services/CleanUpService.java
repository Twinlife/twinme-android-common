/*
 *  Copyright (c) 2023-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.export.ExportExecutor;
import org.twinlife.twinme.export.ExportObserver;
import org.twinlife.twinme.export.ExportState;
import org.twinlife.twinme.export.ExportStats;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class CleanUpService extends AbstractTwinmeService implements ExportObserver {
    private static final String LOG_TAG = "CleanUpService";
    private static final boolean DEBUG = false;

    private static final int GET_SPACE = 2;
    private static final int GET_SPACE_DONE = 1 << 3;
    private static final int GET_GROUP = 1 << 4;
    private static final int GET_GROUP_DONE = 1 << 5;
    private static final int GET_CONTACT = 1 << 6;
    private static final int GET_CONTACT_DONE = 1 << 7;

    public interface Observer extends AbstractTwinmeService.Observer, AbstractTwinmeService.ContactObserver,
            AbstractTwinmeService.GroupObserver, ExportObserver {

        void onGetSpace(@NonNull Space space);

        void onClearConversation();
    }

    @Nullable
    private Observer mObserver;
    private final UUID mGroupId;
    private final UUID mContactId;
    private final UUID mSpaceId;

    private List<Group> mGroups;
    private List<Contact> mContacts;
    @Nullable
    private Space mSpace;

    private final List<Conversation> mConversations;
    private int mState = 0;
    private int mWork = 0;
    @NonNull
    private final ExportExecutor mExport;

    public CleanUpService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
                          @Nullable UUID spaceId, @Nullable UUID contactId, @Nullable UUID groupId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ExportService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mSpaceId = spaceId;
        mContactId = contactId;
        mGroupId = groupId;
        if (spaceId != null) {
            mWork |= GET_SPACE;
        } else if (contactId != null) {
            mWork |= GET_CONTACT;
        } else if (groupId != null) {
            mWork |= GET_GROUP;
        } else {
            mWork |= GET_SPACE;
        }

        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversations = new ArrayList<>();
        mExport = new ExportExecutor(twinmeContext, this, true, (mWork & GET_SPACE) != 0);
        showProgressIndicator();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void setTypeFilter(@NonNull ConversationService.Descriptor.Type[] types) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setTypeFilter");
        }

        mExport.setTypeFilter(types);
    }

    public void setDateFilter(long clearDate) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setDateFilter");
        }

        // Avoid blocking the UI thread: run the prepare operations from the twinlife executor's thread.
        mTwinmeContext.execute(() -> {
            mExport.setDateFilter(clearDate);

            if (mContacts != null) {
                mExport.prepareContacts(mContacts);
            } else if (mGroups != null) {
                mExport.prepareGroups(mGroups);
            } else if (mSpace != null) {
                mExport.prepareSpace(mSpace);
            } else {
                mExport.prepareAll();
            }
        });
    }

    public void startCleanUp(long clearDate, ConversationService.ClearMode clearMode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startCleanUp");
        }

        mTwinmeContext.execute(() -> {
            final List<Conversation> conversations = mExport.getConversations();
            if (conversations != null) {
                mConversations.addAll(conversations);
            }
            for (Conversation conversation : mConversations) {
                mTwinmeContext.getConversationService().clearConversation(conversation, clearDate, clearMode);
            }
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onClearConversation();
                }
            });
        });
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mExport.dispose();
        mObserver = null;

        super.dispose();
    }

    /**
     * Give information about the exporter progress.
     *
     * @param state the current export state.
     * @param stats the current stats about the export.
     */
    @Override
    public void onProgress(@NonNull ExportState state, @NonNull ExportStats stats) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onProgress state=" + state + " stats=" + stats);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onProgress(state, stats);
            }
        });
    }

    /**
     * Report an error raised while exporting medias.
     *
     * @param message the error message.
     */
    @Override
    public void onError(@NonNull String message) {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onError(message);
            }
        });
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact: contact=" + contact);
        }

        mState |= GET_CONTACT_DONE;

        if (contact != null) {
            runOnGetContact(mObserver, contact, null);

            if (contact.getTwincodeOutboundId() != null) {
                ConversationService.Conversation conversation = mTwinmeContext.getConversationService().getConversation(contact);
                if (conversation != null) {
                    mConversations.add(conversation);
                }
            }
            mContacts = Collections.singletonList(contact);
            mExport.prepareContacts(mContacts);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_CONTACT, errorCode, null);
        }

        onOperation();
    }

    private void onGetGroup(@NonNull ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroup: group=" + group);
        }

        mState |= GET_GROUP_DONE;
        if (group != null) {

            mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_ID, group.getId(), mGroupId);

            runOnGetGroup(mObserver, group, null);

            if (group.getTwincodeOutboundId() != null) {
                ConversationService.Conversation conversation = mTwinmeContext.getConversationService().getConversation(group);
                if (conversation != null) {
                    mConversations.add(conversation);
                }
            }
            mGroups = Collections.singletonList(group);
            mExport.prepareGroups(mGroups);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetGroupNotFound(mObserver);
        } else {
            onError(GET_GROUP, errorCode, null);
        }

        onOperation();
    }

    private void onGetSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetSpace: space=" + space);
        }

        mState |= GET_SPACE_DONE;
        mSpace = space;

        // Be careful: after a fresh installation, there is no space (nothing to cleanup but don't crash).
        if (mObserver != null && space != null) {
            mObserver.onGetSpace(space);
            mSpace = space;
            mExport.prepareSpace(space);
        }

        onOperation();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {
            return;
        }

        // We must get the space object or trigger scanning of every space.
        if ((mWork & GET_SPACE) != 0) {
            if ((mState & GET_SPACE) == 0) {
                mState |= GET_SPACE;
                if (mSpaceId == null) {
                    mExport.prepareAll();
                    mState |= GET_SPACE_DONE;
                } else {
                    mTwinmeContext.getSpace(mSpaceId, this::onGetSpace);
                }
                return;
            }
            if ((mState & GET_SPACE_DONE) == 0) {
                return;
            }
        }

        // We must get the group object.
        if ((mWork & GET_GROUP) != 0) {
            if (mGroupId != null && (mState & GET_GROUP) == 0) {
                mState |= GET_GROUP;
                mTwinmeContext.getGroup(mGroupId, this::onGetGroup);
                return;
            }
            if ((mState & GET_GROUP_DONE) == 0) {
                return;
            }
        }

        // We must get the contact.
        if ((mWork & GET_CONTACT) != 0 && mContactId != null) {
            if ((mState & GET_CONTACT) == 0) {
                mState |= GET_CONTACT;
                mTwinmeContext.getContact(mContactId, this::onGetContact);
                return;
            }
            if ((mState & GET_CONTACT_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }
}