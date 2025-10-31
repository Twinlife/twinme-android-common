/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.RepositoryService;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.configuration.AppFlavor;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;
import org.twinlife.twinme.ui.TwinmeApplication;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Small service used by the SplashScreen to wait that the TwinmeContext library has fully loaded the contacts.
 * It is useful on devices which have a lot of contacts because loading them may take several seconds.
 */
public class SplashService extends AbstractTwinmeService {
    private static final String LOG_TAG = "SplashService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_CONTACTS = 1 << 2;
    private static final int GET_CONTACTS_DONE = 1 << 3;
    private static final int GET_CONVERSATIONS = 1 << 4;
    private static final int GET_CONVERSATIONS_DONE = 1 << 5;

    private static final int PROBE_UPGRADE_DELAY = 200; // Check each 200 ms

    public interface Observer extends AbstractTwinmeService.Observer {

        void onFatalError(ErrorCode errorCode);

        void onState(TwinmeApplication.State state);

        void onReady(boolean hasProfiles, boolean hasContacts, boolean hasConversations);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onSignInError(@NonNull ErrorCode errorCode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSignInError errorCode=" + errorCode);
            }

            SplashService.this.onFatalError(errorCode);
        }

        @Override
        public void onFatalError(ErrorCode errorCode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onFatalError: errorCode=" + errorCode);
            }

            SplashService.this.onFatalError(errorCode);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mState = 0;
    private ScheduledFuture<?> mTimer;
    private final long mStartTime = System.currentTimeMillis();
    private UUID mActiveDeviceMigrationId;
    private boolean mHasProfiles;
    private boolean mHasContacts;
    private Space mSpace;

    public SplashService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "SplashService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
        mHasProfiles = false;
        mHasContacts = false;
        // When a database upgrade is made, the onTwinlifeReady() is not called immediately but after some delay
        // which depends on the migration.  The isDatabaseUpgraded() will be set sometimes in a near future but we
        // cannot have a callback to be notified.  We also want to display some "Upgrading" message to the user as
        // soon as possible.  Setup a timer to test if the database upgrade is in progress.
        // The timer is scheduled each 200ms until onTwinlifeReady() is called.
        checkDatabaseUpgrade();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        EventMonitor.event("onTwinlifeReady", mStartTime);

        if (mTimer != null) {
            mTimer.cancel(false);
            mTimer = null;
        }

        mActiveDeviceMigrationId = mTwinmeContext.getAccountMigrationService().getActiveDeviceMigrationId();
        runOnUiThread(() -> {
            if (mActiveDeviceMigrationId != null && mObserver != null) {
                mObserver.onState(TwinmeApplication.State.MIGRATION);
            } else if (mTwinmeContext.isDatabaseUpgraded() && mObserver != null) {
                mObserver.onState(TwinmeApplication.State.UPGRADING);
            }
        });
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTimer != null) {
            mTimer.cancel(false);
            mTimer = null;
        }

        mObserver = null;
        super.dispose();
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: Get the current space.
        //
        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                mState |= GET_CURRENT_SPACE_DONE;

                if (!AppFlavor.TWINME_PLUS) {
                    //Twinme has only one space
                    mHasProfiles = space != null && space.getProfile() != null;
                } else {
                    RepositoryService repositoryService = mTwinmeContext.getRepositoryService();

                    // The default space can have no profile but there is another space with a profile.
                    mHasProfiles = repositoryService.hasObjects(Profile.SCHEMA_ID);
                }

                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2: We must get the list of contacts for the space.
        //
        if ((mState & GET_CONTACTS) == 0) {
            mState |= GET_CONTACTS;

            final Filter<RepositoryObject> filter = new Filter<>(mSpace);
            mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
                mState |= GET_CONTACTS_DONE;
                mHasContacts = !contacts.isEmpty();
                onOperation();
            });
            return;
        }
        if ((mState & GET_CONTACTS_DONE) == 0) {
            return;
        }

        //
        // Step 3: We must get the list of conversations for the space
        //

        if ((mState & GET_CONVERSATIONS) == 0) {
            mState |= GET_CONVERSATIONS;

            Filter<ConversationService.Conversation> filter = new Filter<>(mSpace);
            mTwinmeContext.findConversations(filter, (List<ConversationService.Conversation> conversations) -> {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onReady(mHasProfiles, mHasContacts, !conversations.isEmpty());
                    }
                });
                mState |= GET_CONVERSATIONS_DONE;

                mTwinmeContext.setDynamicShortcuts();

                onOperation();
            });
            return;
        }
        if ((mState & GET_CONVERSATIONS_DONE) == 0) {
            return;
        }

        hideProgressIndicator();

        if (mActiveDeviceMigrationId == null) {
            mTwinmeApplication.setReady();
        }
    }

    private void checkDatabaseUpgrade() {
        if (DEBUG) {
            Log.d(LOG_TAG, "probUpgrade");
        }

        if (mTwinmeContext.isDatabaseUpgraded()) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onState(TwinmeApplication.State.UPGRADING);
                }
            });
            mTimer = null;
        } else {
            mTimer = mTwinmeContext.getJobService().schedule(this::checkDatabaseUpgrade, PROBE_UPGRADE_DELAY);
        }
    }

    private void onFatalError(ErrorCode errorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onFatalError: errorCode=" + errorCode);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onFatalError(errorCode);
            }
        });
    }
}
