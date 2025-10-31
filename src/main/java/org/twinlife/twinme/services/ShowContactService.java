/*
 *  Copyright (c) 2017-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.Consumer;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.TrustMethod;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.UUID;

public class ShowContactService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ShowContactService";
    private static final boolean DEBUG = false;

    private static final int GET_CONTACT = 1;
    private static final int GET_CONTACT_DONE = 1 << 1;
    private static final int GET_CONTACT_THUMBNAIL_IMAGE = 1 << 2;
    private static final int GET_CONTACT_THUMBNAIL_IMAGE_DONE = 1 << 3;
    private static final int GET_CONTACT_IMAGE = 1 << 4;
    private static final int GET_CONTACT_IMAGE_DONE = 1 << 5;
    private static final int DELETE_CONTACT = 1 << 6;
    private static final int DELETE_CONTACT_DONE = 1 << 7;

    public interface Observer extends AbstractTwinmeService.Observer, ContactObserver {

        void onUpdateImage(@NonNull Bitmap avatar);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            if (!mContactId.equals(contactId)) {

                return;
            }

            finishOperation(requestId);

            ShowContactService.this.onDeleteContact(contactId);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (!mContactId.equals(contact.getId())) {

                return;
            }
            finishOperation(requestId);

            ShowContactService.this.onUpdateContact(contact);
        }
    }

    @Nullable
    protected Observer mObserver;
    @NonNull
    protected final UUID mContactId;
    @Nullable
    protected Contact mContact;
    @Nullable
    protected ImageId mAvatarId;
    @Nullable
    protected Bitmap mAvatar;
    protected int mState;
    protected int mWork;
    protected boolean mHasPrivatePeer;

    public ShowContactService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer,
                              @NonNull UUID contactId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ShowContactService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mContactId = contactId;

        mTwinmeContextObserver = new TwinmeContextObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
    }

    public void deleteContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteContact: contact=" + contact);
        }

        mContact = contact;
        mWork |= DELETE_CONTACT;
        showProgressIndicator();
        startOperation();
    }

    public void createAuthenticateURI(@NonNull Consumer<TwincodeURI> complete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createAuthenticateURI: contact=" + mContact);
        }

        if (mContact != null && mContact.getTwincodeOutbound() != null) {
            createURI(TwincodeURI.Kind.Authenticate, mContact.getTwincodeOutbound(), complete);
        } else {
            complete.onGet(ErrorCode.ITEM_NOT_FOUND, null);
        }
    }

    public void verifyAuthenticateURI(@NonNull Uri uri, @NonNull TwinmeContext.ConsumerWithError<Contact> complete) {
        if (DEBUG) {
            Log.d(LOG_TAG, "verifyAuthenticateURI: uri=" + uri);
        }

        parseURI(uri, (ErrorCode errorCode, TwincodeURI twincodeURI) -> {
            if (errorCode != ErrorCode.SUCCESS || twincodeURI == null) {
                runOnUiThread(() -> complete.onGet(errorCode, null));
            } else {
                mTwinmeContext.verifyContact(twincodeURI, TrustMethod.QR_CODE,
                        (ErrorCode lErrorCode, Contact contact) -> runOnUiThread(() -> complete.onGet(lErrorCode, contact)));
            }
        });
    }

    //
    // Private methods
    //

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact: contact=" + contact);
        }

        mState |= GET_CONTACT_DONE;
        if (contact != null) {
            // Record the private peer state as well as the avatar Id to detect a change.
            mContact = contact;
            mHasPrivatePeer = contact.hasPrivatePeer();
            mAvatarId = contact.getAvatarId();
            mState &= ~(GET_CONTACT_IMAGE | GET_CONTACT_IMAGE_DONE);
            final Bitmap avatar = getImage(contact);
            mAvatar = avatar;
            if (mAvatarId != null && avatar == null) {
                mState &= ~(GET_CONTACT_THUMBNAIL_IMAGE | GET_CONTACT_THUMBNAIL_IMAGE_DONE);
            }
            runOnGetContact(mObserver, contact, avatar);
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_CONTACT, errorCode, null);
        }
        onOperation();
    }

    protected void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        mContact = contact;

        // Check if the image was modified.
        if (mAvatarId == null || !mAvatarId.equals(contact.getAvatarId())) {
            mAvatarId = contact.getAvatarId();

            // Subtle behavior happen when we scan a peer invitation: the initial peer identity
            // corresponds to its invitation twincode.  Once the peer has received our pair::invite,
            // it gives us its real identity through the pair::bind and we get a new avatar (which
            // is a copy of the invitation image).  If we switch to the new avatar immediately,
            // the user may see a sequence of:
            //  - thumbnail display (256x256), |
            //  - normal image display,        |__ Image from invitation profile
            //  - thumbnail display (256x256), |
            //  - normal image display         |__ Image from the peer identity
            // To avoid the second thumbnail display, we skip the image update if we don't have
            // the private identity when onGetContact() is called.
            if (mHasPrivatePeer) {
                mAvatar = getImage(contact);
            }
            mState &= ~(GET_CONTACT_THUMBNAIL_IMAGE | GET_CONTACT_THUMBNAIL_IMAGE_DONE | GET_CONTACT_IMAGE | GET_CONTACT_IMAGE_DONE);
        }
        Bitmap avatar = mAvatar;
        runOnUpdateContact(mObserver, contact, avatar);
        onOperation();
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        mState |= DELETE_CONTACT_DONE;
        mWork &= ~DELETE_CONTACT;

        runOnDeleteContact(mObserver, contactId);
        onOperation();
    }

    //
    // Private methods
    //
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: Get the current contact.
        //
        if ((mState & GET_CONTACT) == 0) {
            mState |= GET_CONTACT;

            mTwinmeContext.getContact(mContactId, this::onGetContact);
            return;
        }
        if ((mState & GET_CONTACT_DONE) == 0) {
            return;
        }

        //
        // Step 2: Get the group thumbnail image if we can.
        //
        if (mAvatarId != null && mAvatar == null) {
            if ((mState & GET_CONTACT_THUMBNAIL_IMAGE) == 0) {
                mState |= GET_CONTACT_THUMBNAIL_IMAGE;

                mTwinmeContext.getImageService().getImageFromServer(mAvatarId, ImageService.Kind.THUMBNAIL, (ErrorCode errorCode, Bitmap image) -> {
                    mState |= GET_CONTACT_THUMBNAIL_IMAGE_DONE;

                    if (image != null) {
                        mAvatar = image;
                        runOnUiThread(() -> {
                            if (mObserver != null) {
                                mObserver.onUpdateImage(image);
                            }
                        });
                    }
                    onOperation();
                });
                return;
            }
            if ((mState & GET_CONTACT_THUMBNAIL_IMAGE_DONE) == 0) {
                return;
            }
        }

        //
        // Step 3: Get the contact normal image if we can.
        //
        if (mAvatarId != null) {
            if ((mState & GET_CONTACT_IMAGE) == 0) {
                mState |= GET_CONTACT_IMAGE;

                final ImageId avatarId = mAvatarId;
                mTwinmeContext.getImageService().getImageFromServer(avatarId, ImageService.Kind.NORMAL, (ErrorCode errorCode, Bitmap image) -> {
                    mState |= GET_CONTACT_IMAGE_DONE;

                    // We must verify this image corresponds to the current one because it could have
                    // been changed while we were getting it.
                    if (image != null && mContact != null && avatarId.equals(mAvatarId)) {
                        mAvatar = image;
                        mHasPrivatePeer = mContact.hasPrivatePeer();
                        runOnUiThread(() -> {
                            if (mObserver != null) {
                                mObserver.onUpdateImage(image);
                            }
                        });
                    }
                    onOperation();
                });
                return;
            }
            if ((mState & GET_CONTACT_IMAGE_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: delete the contact.
        //
        if (mContact != null && (mWork & DELETE_CONTACT) != 0) {
            if ((mState & DELETE_CONTACT) == 0) {
                mState |= DELETE_CONTACT;

                long requestId = newOperation(DELETE_CONTACT);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.deleteContact: requestId=" + requestId + " contact=" + mContact);
                }

                mTwinmeContext.deleteContact(requestId, mContact);
                return;
            }
            if ((mState & DELETE_CONTACT_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        if (mWork == 0) {
            hideProgressIndicator();
        }
    }

    @Override
    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // Wait for reconnection
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;

            return;
        }

        if (errorCode == ErrorCode.ITEM_NOT_FOUND && operationId == DELETE_CONTACT) {
            onDeleteContact(mContactId);
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
