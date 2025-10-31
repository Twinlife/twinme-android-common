/*
 *  Copyright (c) 2018-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.ConnectionStatus;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;
import org.twinlife.twinme.ui.TwinmeApplication;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base class of a Twinme service.
 * <p>
 * This class provides various operations that are common to several Twinme services:
 * <p>
 * - Detecting connection and disconnections through the onConnect() and onDisconnect(),
 * - Showing and hiding a progress indicator,
 * - Executing new operations on TwinmeContext.
 */
public class AbstractTwinmeService {
    private static final String LOG_TAG = "AbstractTwinmeService";
    private static final boolean DEBUG = false;

    public interface Observer {

        void showProgressIndicator();

        void hideProgressIndicator();
    }

    /**
     * Contact observer: onGetContact() and onGetContactNotFound() ARE MANDATORY METHODS.
     */
    public interface ContactObserver {

        void onGetContact(@NonNull Contact contact, @Nullable Bitmap avatar);

        void onGetContactNotFound();

        void onUpdateContact(@NonNull Contact contact, @Nullable Bitmap avatar);

        // Optional methods
        default void onDeleteContact(@NonNull UUID contactId) {}
    }

    public interface GroupObserver {

        void onGetGroup(@NonNull Group contact, @Nullable Bitmap avatar);

        void onGetGroupNotFound();

        // void onUpdateContact(@NonNull Contact contact, @Nullable Bitmap avatar);
    }

    public interface SpaceObserver {

        default void onGetSpace(@NonNull Space space, @Nullable Bitmap avatar) {}

        default void onGetSpaceNotFound() {}
    }

    public interface CurrentSpaceObserver {

        void onSetCurrentSpace(@NonNull Space space);
    }

    public interface ContactListObserver {

        void onGetContacts(@NonNull List<Contact> contacts);
    }

    public interface GroupListObserver {

        void onGetGroups(@NonNull List<Group> groups);
    }

    public interface SpaceListObserver {

        void onGetSpaces(@NonNull List<Space> spaces);
    }

    public interface TwincodeObserver {

        void onGetTwincode(@NonNull TwincodeOutbound twincode, @Nullable Bitmap avatar);

        void onGetTwincodeNotFound();
    }

    protected class TwinmeContextObserver extends TwinmeContext.DefaultObserver {

        @Override
        public void onTwinlifeReady() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeReady");
            }

            AbstractTwinmeService.this.onTwinlifeReady();
            onOperation();
        }

        @Override
        public void onTwinlifeOnline() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeOnline");
            }

            AbstractTwinmeService.this.onTwinlifeOnline();
            onOperation();
        }

        @Override
        public void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onConnectionStatusChange " + connectionStatus);
            }

            AbstractTwinmeService.this.onConnectionStatusChange(connectionStatus);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace");
            }

            AbstractTwinmeService.this.onSetCurrentSpace(space);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            if (errorCode == ErrorCode.NO_STORAGE_SPACE) {
                runOnUiThread(() -> AbstractTwinmeService.this.onStorageError(errorCode));
                return;
            }

            Integer operationId;
            synchronized (mRequestIds) {
                operationId = mRequestIds.remove(requestId);
                if (operationId == null) {

                    return;
                }
            }

            AbstractTwinmeService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @Nullable
    private TwinmeActivity mActivity;
    @NonNull
    protected final TwinmeContext mTwinmeContext;
    @NonNull
    protected final TwinmeApplication mTwinmeApplication;
    @Nullable
    private Observer mBaseObserver;
    private boolean mConnected;

    @SuppressLint("UseSparseArrays")
    protected final Map<Long, Integer> mRequestIds = new HashMap<>();
    @SuppressLint("UseSparseArrays")
    private final Map<Long, Long> mStartTimes = new HashMap<>();
    private final String mLogTag;
    protected boolean mRestarted = false;
    protected boolean mIsTwinlifeReady = false;

    protected TwinmeContextObserver mTwinmeContextObserver;

    public AbstractTwinmeService(@NonNull String logTag, @NonNull TwinmeActivity activity,
                                 @NonNull TwinmeContext twinmeContext, @Nullable Observer observer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "AbstractTwinmeService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mLogTag = logTag;
        mActivity = activity;
        mTwinmeApplication = activity.getTwinmeApplication();
        mTwinmeContext = twinmeContext;
        mBaseObserver = observer;
        mConnected = twinmeContext.isConnected();
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mTwinmeContext.removeObserver(mTwinmeContextObserver);

        // Invalidate the observer and activity so that the activity can be reclaimed by the GC.
        mBaseObserver = null;
        mActivity = null;
    }

    //
    // Private methods
    //

    public long newOperation(int operationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "newOperation: operationId=" + operationId);
        }

        long requestId = mTwinmeContext.newRequestId();
        synchronized (mRequestIds) {
            mRequestIds.put(requestId, operationId);
            mStartTimes.put(requestId, System.currentTimeMillis());
        }

        return requestId;
    }

    public synchronized Integer getOperation(long requestId) {

        final Integer operationId = mRequestIds.remove(requestId);
        if (operationId != null) {
            Long start = mStartTimes.remove(requestId);
            if (start != null) {
                EventMonitor.event(mLogTag + " " + operationId, start);
            }
        }
        return operationId;
    }

    public synchronized void finishOperation(long requestId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "finishOperation: requestId=" + requestId);
        }

        final Integer operationId = mRequestIds.remove(requestId);
        if (operationId != null) {
            Long start = mStartTimes.remove(requestId);
            if (start != null) {
                EventMonitor.event(mLogTag + " " + operationId, start);
            }
        }
    }

    @AnyThread
    @Nullable
    protected Bitmap getCachedImage(@Nullable ImageId avatarId, @Nullable Bitmap defaultAvatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCachedImage: avatarId=" + avatarId);
        }

        if (avatarId == null) {

            return defaultAvatar;
        }

        ImageService imageService = mTwinmeContext.getImageService();

        return imageService.getCachedImage(avatarId, ImageService.Kind.THUMBNAIL);
    }

    @UiThread
    public void getImage(@Nullable ImageId imageId, TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getImage (async): imageId=" + imageId);
        }

        if (!Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(311));
        }

        Bitmap cachedImage = getCachedImage(imageId, null);
        if (cachedImage != null) {
            uiConsumer.accept(cachedImage);
            return;
        }

        mTwinmeContext.executeImage(() -> {
            Bitmap image = getImage(imageId);
            runOnUiThread(() -> uiConsumer.accept(image));
        });
    }

    @WorkerThread
    @Nullable
    public Bitmap getImage(@Nullable ImageId imageId) {
        return getImage(imageId, ImageService.Kind.THUMBNAIL);
    }
    @WorkerThread
    @Nullable
    public Bitmap getImage(@Nullable ImageId imageId, @NonNull ImageService.Kind kind) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getImage: imageId=" + imageId);
        }

        if (Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(339));
        }

        if (imageId == null) {
            return null;
        }

        return mTwinmeContext.getImageService().getImage(imageId, kind);

    }

    @MainThread
    public void getImageOrDefaultAvatar(@Nullable Originator originator, TwinmeContext.Consumer<Bitmap> uiConsumer) {
        getImage(originator, (Bitmap avatar) -> {
            if (avatar == null) {
                if (originator != null && originator.isGroup()) {
                    uiConsumer.accept(mTwinmeApplication.getDefaultGroupAvatar());
                } else {
                    uiConsumer.accept(mTwinmeApplication.getDefaultAvatar());
                }
            } else {
                uiConsumer.accept(avatar);
            }
        });
    }

    @MainThread
    public void getImage(@Nullable Originator originator, TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getImage (async): originator=" + originator);
        }

        if (!Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(373));
        }

        if (originator == null) {
            uiConsumer.accept(mTwinmeApplication.getDefaultAvatar());
            return;
        }

        Bitmap defaultAvatar;
        if (originator.isGroup()) {
            defaultAvatar = mTwinmeApplication.getDefaultGroupAvatar();
        } else {
            defaultAvatar = mTwinmeApplication.getDefaultAvatar();
        }

        Bitmap cachedImage = getCachedImage(originator.getAvatarId(), defaultAvatar);
        if (cachedImage != null) {
            uiConsumer.accept(cachedImage);
            return;
        }

        mTwinmeContext.executeImage(() -> getImageFromServer(originator, uiConsumer));
    }

    @WorkerThread
    @Nullable
    public Bitmap getImage(@Nullable Originator originator) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getImage: originator=" + originator);
        }

        if (Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(405));
        }

        if (originator == null || originator.getAvatarId() == null) {
            return (originator != null && originator.isGroup()) ?
                    mTwinmeApplication.getDefaultGroupAvatar() :
                    mTwinmeApplication.getDefaultAvatar();
        }

        return mTwinmeContext.getImageService().getImage(originator.getAvatarId(), ImageService.Kind.THUMBNAIL);

    }

    @WorkerThread
    @Nullable
    public Bitmap getTwincodeImage(@Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getTwincodeImage: twincodeOutbound=" + twincodeOutbound);
        }

        if (Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(425));
        }

        if (twincodeOutbound == null || twincodeOutbound.getAvatarId() == null) {
            return null;
        }

        return mTwinmeContext.getImageService().getImage(twincodeOutbound.getAvatarId(), ImageService.Kind.THUMBNAIL);
    }

    @UiThread
    public void getProfileImage(@Nullable Profile profile, @NonNull TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getProfileImage (async): profile=" + profile);
        }

        if (!Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(443));
        }

        if (profile == null) {
            uiConsumer.accept(mTwinmeApplication.getDefaultAvatar());
            return;
        }

        Bitmap cachedImage = getCachedImage(profile.getAvatarId(), mTwinmeApplication.getDefaultAvatar());
        if (cachedImage != null) {
            uiConsumer.accept(cachedImage);
            return;
        }

        mTwinmeContext.executeImage(() -> {
            Bitmap image = getProfileImage(profile);
            runOnUiThread(() -> uiConsumer.accept(image));
        });
    }

    @WorkerThread
    @Nullable
    private Bitmap getProfileImage(@Nullable Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getProfileImage: profile=" + profile);
        }

        if (profile == null || profile.getAvatarId() == null) {

            return mTwinmeApplication.getDefaultAvatar();
        }

        return mTwinmeContext.getImageService().getImage(profile.getAvatarId(), ImageService.Kind.THUMBNAIL);
    }

    @UiThread
    public void getIdentityImage(@Nullable Originator originator, TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getIdentityImage (async): originator=" + originator);
        }

        if (!Utils.isMainThread()) {
            // "getImage async MUST be called from main UI thread"
            mTwinmeContext.assertion(ServiceAssertPoint.GET_IDENTITY_IMAGE, null);
        }

        if (originator == null) {
            uiConsumer.accept(mTwinmeApplication.getDefaultAvatar());
            return;
        }

        Bitmap cachedImage = getCachedImage(originator.getIdentityAvatarId(), mTwinmeApplication.getDefaultAvatar());
        if (cachedImage != null) {
            uiConsumer.accept(cachedImage);
            return;
        }

        mTwinmeContext.executeImage(() -> {
            Bitmap image = getIdentityImage(originator);
            runOnUiThread(() -> uiConsumer.accept(image));
        });
    }

    @WorkerThread
    @Nullable
    public Bitmap getIdentityImage(@Nullable Originator originator) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getIdentityImage: originator=" + originator);
        }

        if (originator == null) {

            return mTwinmeApplication.getDefaultAvatar();
        }

        ImageId avatarId = originator.getIdentityAvatarId();

        return getImage(avatarId);
    }

    @UiThread
    public void getGroupMemberImage(@Nullable GroupMember groupMember, @NonNull TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroupMemberImage: groupMember=" + groupMember);
        }

        if (!Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(529));
        }

        if (groupMember == null || groupMember.getAvatarId() == null) {
            uiConsumer.accept(mTwinmeApplication.getDefaultAvatar());
            return;
        }

        mTwinmeContext.executeImage(() -> {
            Bitmap image = mTwinmeContext.getImageService().getImage(groupMember.getAvatarId(), ImageService.Kind.THUMBNAIL);
            runOnUiThread(() -> uiConsumer.accept(image));
        });
    }

    @WorkerThread
    @Nullable
    public Bitmap getSpaceImage(@Nullable Space space) {
        return getSpaceImage(space, ImageService.Kind.THUMBNAIL);
    }

    @WorkerThread
    @Nullable
    public Bitmap getSpaceImage(@Nullable Space space, @NonNull ImageService.Kind kind) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpaceImage space=" + space);
        }

        if (space == null) {

            return null;
        }

        ImageId avatarId = space.getAvatarId();
        if (avatarId != null) {
            return getImage(avatarId);
        }
        UUID imageId = space.getSpaceAvatarId();
        if (imageId == null) {
            return null;
        }
        avatarId = mTwinmeContext.getImageService().getImageId(imageId);
        if (avatarId == null) {
            return null;
        }
        return getImage(avatarId, kind);
    }

    @UiThread
    public void getSpaceImage(@Nullable Space space, @NonNull TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpaceImage: space=" + space);
        }

        if (!Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(583));
        }

        if (space == null || !space.hasSpaceAvatar()) {
            uiConsumer.accept(null);
            return;
        }

        mTwinmeContext.executeImage(() -> {
            Bitmap image = getSpaceImage(space);
            runOnUiThread(() -> uiConsumer.accept(image));
        });
    }

    public void getImageFromServer(@NonNull Originator originator) {
        getImageFromServer(originator, null);
    }

    public void getImageFromServer(@NonNull Originator originator, @Nullable TwinmeContext.Consumer<Bitmap> uiConsumer) {
        getImageFromServer(originator, ImageService.Kind.THUMBNAIL, uiConsumer);
    }

    public void getImageFromServer(@NonNull Originator originator, @NonNull ImageService.Kind kind, @Nullable TwinmeContext.Consumer<Bitmap> uiConsumer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getImageFromServer: originator=" + originator);
        }

        ImageId avatarId = originator.getAvatarId();
        if (avatarId == null) {
            if (uiConsumer != null) {
                runOnUiThread(() -> uiConsumer.accept(null));
            }
            return;
        }

        mTwinmeContext.executeImage(() ->
                mTwinmeContext.getImageService().getImageFromServer(avatarId, kind, (ErrorCode errorCode, Bitmap image) -> {
                    if (uiConsumer != null) {
                        runOnUiThread(() -> uiConsumer.accept(image));
                    }

                    if (errorCode != ErrorCode.SUCCESS || image == null) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "getImageFromServer: couldn't get avatar for originator: " + originator + ": " + errorCode);
                        }
                        return;
                    }

                    notifyObserver(originator, image);
                }));
    }

    public void parseURI(@NonNull Uri uri, @NonNull Consumer<TwincodeURI> complete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "parseURI uri=" + uri);
        }

        mTwinmeContext.execute(() -> mTwinmeContext.getTwincodeOutboundService().parseURI(uri, (ErrorCode errorCode, TwincodeURI result)
                -> runOnUiThread(() -> complete.onGet(errorCode, result))));
    }

    public void createURI(@NonNull TwincodeURI.Kind kind, @NonNull TwincodeOutbound twincodeOutbound, @NonNull Consumer<TwincodeURI> complete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createURI uri=" + twincodeOutbound);
        }

        mTwinmeContext.execute(() -> mTwinmeContext.getTwincodeOutboundService().createURI(kind, twincodeOutbound, (ErrorCode errorCode, TwincodeURI result)
                -> runOnUiThread(() -> complete.onGet(errorCode, result))));
    }

    private void notifyObserver(@NonNull Originator originator, Bitmap image) {
        Observer baseObserver = mBaseObserver;

        if (baseObserver == null) {
            return;
        }

        switch (originator.getType()) {
            case CONTACT:
                if (baseObserver instanceof ContactObserver) {
                    runOnUiThread(() -> ((ContactObserver) baseObserver).onUpdateContact((Contact) originator, image));
                }
                break;
            case CALL_RECEIVER:
                if (baseObserver instanceof CallReceiverService.Observer) {
                    runOnUiThread(() -> ((CallReceiverService.Observer) baseObserver).onUpdateCallReceiverAvatar(image));
                }
                break;
            case GROUP:
                if (baseObserver instanceof GroupObserver) {
                    runOnUiThread(() -> ((GroupObserver) baseObserver).onGetGroup((Group) originator, image));
                }
                break;
            default:
                if (DEBUG) {
                    Log.d(LOG_TAG, "getImageFromServer: no observer for originator: " + originator);
                }
                break;
        }
    }

    /**
     * Normalize the text before doing a comparison for a name search.
     *
     * @param text the text to normalize
     * @return the normalized text.
     */
    public static String normalize(@NonNull String text) {

        return Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    public boolean isConnected() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isConnected");
        }

        return mConnected;
    }

    protected void runOnUiThread(@NonNull Runnable runnable) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnUiThread");
        }

        TwinmeActivity activity = mActivity;
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    //
    // Run ContactObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetContact(@Nullable ContactObserver observer, @NonNull Contact contact, @Nullable Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetContact contact=" + contact);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetContact(contact, avatar));
        }
    }

    protected void runOnUpdateContact(@Nullable ContactObserver observer, @NonNull Contact contact, @Nullable Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnUpdateContact contact=" + contact);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onUpdateContact(contact, avatar));
        }
    }

    protected void runOnGetContactNotFound(@Nullable ContactObserver observer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetContactNotFound");
        }

        if (observer != null) {
            runOnUiThread(observer::onGetContactNotFound);
        }
    }

    protected void runOnDeleteContact(@Nullable ContactObserver observer, @NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnDeleteContact contactId=" + contactId);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onDeleteContact(contactId));
        }
    }

    //
    // Run GroupObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetGroup(@Nullable GroupObserver observer, @NonNull Group group, @Nullable Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetContact group=" + group);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetGroup(group, avatar));
        }
    }

    protected void runOnGetGroupNotFound(@Nullable GroupObserver observer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetGroupNotFound");
        }

        if (observer != null) {
            runOnUiThread(observer::onGetGroupNotFound);
        }
    }

    //
    // Run SpaceObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetSpace(@Nullable SpaceObserver observer, @Nullable Space space, @Nullable Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetSpace space=" + space);
        }

        if (observer != null) {
            runOnUiThread(() -> {
                if (space != null) {
                    observer.onGetSpace(space, avatar);
                } else {
                    observer.onGetSpaceNotFound();
                }
            });
        }
    }

    //
    // Run CurrentSpaceObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnSetCurrentSpace(@Nullable CurrentSpaceObserver observer, @NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnSetCurrentSpace space=" + space);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onSetCurrentSpace(space));
        }
    }

    //
    // Run ContactListObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetContacts(@Nullable ContactListObserver observer, @NonNull List<Contact> contacts) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetContactNotFound");
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetContacts(contacts));
        }
    }

    //
    // Run GroupListObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetGroups(@Nullable GroupListObserver observer, @NonNull List<Group> groups) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetGroups");
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetGroups(groups));
        }
    }

    //
    // Run SpaceListObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetSpaces(@Nullable SpaceListObserver observer, @NonNull List<Space> spaces) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetSpaces");
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetSpaces(spaces));
        }
    }

    //
    // Run TwincodeObserver from the main UI thread if the observer is still valid.
    //

    protected void runOnGetTwincode(@Nullable TwincodeObserver observer, @NonNull TwincodeOutbound twincode, @Nullable Bitmap avatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetTwincode twincode=" + twincode);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetTwincode(twincode, avatar));
        }
    }

    protected void runOnGetTwincodeNotFound(@Nullable TwincodeObserver observer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetTwincodeNotFound");
        }

        if (observer != null) {
            runOnUiThread(observer::onGetTwincodeNotFound);
        }
    }

    protected void runOnGetConversation(@Nullable ShareService.Observer observer, @NonNull ConversationService.Conversation conversation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "runOnGetConversation: observer=" + observer + " conversation=" + conversation);
        }

        if (observer != null) {
            runOnUiThread(() -> observer.onGetConversation(conversation));
        }
    }

    protected void startOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "startOperation");
        }

        mTwinmeContext.execute(this::onOperation);
    }

    protected void showProgressIndicator() {
        if (DEBUG) {
            Log.d(LOG_TAG, "showProgressIndicator");
        }

        if (BuildConfig.ENABLE_CHECKS && !Utils.isMainThread()) {
            mTwinmeContext.assertion(ServiceAssertPoint.MAIN_THREAD, AssertPoint.create(getClass()).putMarker(906));
        }
        if (mBaseObserver != null) {
            mBaseObserver.showProgressIndicator();
        }
    }

    protected void hideProgressIndicator() {
        if (DEBUG) {
            Log.d(LOG_TAG, "hideProgressIndicator");
        }

        runOnUiThread(() -> {
            if (mBaseObserver != null) {
                mBaseObserver.hideProgressIndicator();
            }
        });
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }
    }

    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        mIsTwinlifeReady = true;

        // Trigger the onDisconnect() observer if we are not connected.
        if (!mConnected) {
            onConnectionStatusChange(mTwinmeContext.getConnectionStatus());
        }
        mTwinmeContext.connect();
    }

    @SuppressWarnings("WeakerAccess")
    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;
        }
    }

    protected void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionStatus " + connectionStatus);
        }

        mConnected = connectionStatus == ConnectionStatus.CONNECTED;
    }

    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace");
        }

        TwinmeActivity activity = mActivity;
        if (activity != null) {
            runOnUiThread(() -> activity.onSetCurrentSpace(space));
        }
    }

    protected void onStorageError(@NonNull ErrorCode errorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStorageError: errorCode=" + errorCode);
        }

        TwinmeActivity activity = mActivity;
        if (activity != null) {
            activity.onExecutionError(errorCode);
        }
    }

    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        switch (errorCode) {
            case TWINLIFE_OFFLINE:
                // Wait for reconnection
                mRestarted = true;
                return;

            case TIMEOUT_ERROR:
            case DATABASE_ERROR:
            case BAD_REQUEST:
            case NOT_AUTHORIZED_OPERATION:
            case NO_STORAGE_SPACE:
            case LIMIT_REACHED:
            case ACCOUNT_DELETED:
            case FEATURE_NOT_IMPLEMENTED:
            case LIBRARY_ERROR:
            case FILE_NOT_FOUND:
            case FILE_NOT_SUPPORTED: {
                TwinmeActivity activity = mActivity;
                if (activity != null) {
                    runOnUiThread(() -> activity.onError(errorCode, null, null));
                }
                return;
            }

            default: {
                mTwinmeContext.assertion(ServiceAssertPoint.ON_ERROR, AssertPoint.create(getClass()).put(operationId).put(errorCode));
                TwinmeActivity activity = mActivity;
                if (activity != null) {
                    runOnUiThread(() -> activity.onError(errorCode, null, null));
                }
            }
        }
    }
}
