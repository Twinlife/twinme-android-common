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
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.TwincodeDescriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.Notification;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinlife.TrustMethod;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.configuration.AppFlavor;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.List;
import java.util.UUID;

public class AcceptInvitationService extends AbstractTwinmeService {
    private static final String LOG_TAG = "AcceptInvitationService";
    private static final boolean DEBUG = false;

    private static final int GET_SPACE = 1;
    private static final int GET_SPACE_DONE = 1 << 1;
    private static final int GET_DESCRIPTOR = 1 << 2;
    private static final int PARSE_URI = 1 << 3;
    private static final int PARSE_URI_DONE = 1 << 4;
    private static final int GET_EXISTING_CONTACTS = 1 << 5;
    private static final int GET_EXISTING_CONTACTS_DONE = 1 << 6;
    private static final int GET_TWINCODE = 1 << 7;
    private static final int GET_TWINCODE_DONE = 1 << 8;
    private static final int GET_TWINCODE_IMAGE = 1 << 9;
    private static final int GET_TWINCODE_IMAGE_DONE = 1 << 10;
    private static final int GET_GROUP = 1 << 11;
    private static final int GET_GROUP_DONE = 1 << 12;
    private static final int GET_CONTACT = 1 << 13;
    private static final int GET_CONTACT_DONE = 1 << 14;
    private static final int GET_OR_CREATE_CONVERSATION = 1 << 15;
    private static final int GET_OR_CREATE_CONVERSATION_DONE = 1 << 16;
    private static final int GET_NOTIFICATION = 1 << 17;
    private static final int GET_NOTIFICATION_DONE = 1 << 18;
    private static final int CREATE_CONTACT = 1 << 19;
    private static final int CREATE_CONTACT_DONE = 1 << 20;
    private static final int DELETE_DESCRIPTOR = 1 << 21;
    private static final int DELETE_DESCRIPTOR_DONE = 1 << 22;
    private static final int DELETE_NOTIFICATION = 1 << 23;
    private static final int DELETE_NOTIFICATION_DONE = 1 << 24;
    private static final int SET_CURRENT_SPACE = 1 << 25;
    private static final int SET_CURRENT_SPACE_DONE = 1 << 26;

    public interface Observer extends AbstractTwinmeService.Observer, SpaceObserver, CurrentSpaceObserver, TwincodeObserver {

        void onLocalTwincode();

        void onExistingContacts(@NonNull List<Contact> list);

        void onCreateContact(@NonNull Contact contact);

        void onParseTwincodeURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI uri);

        void onDeleteDescriptor(@NonNull DescriptorId descriptorId);

        void onGetNotification(@NonNull Notification notification);

        void onDeleteNotification(@NonNull UUID notificationId);
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            AcceptInvitationService.this.onCreateContact(contact);
        }

        @Override
        public void onDeleteNotification(long requestId, @NonNull UUID notificationId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteNotification: requestId=" + requestId + " notificationId=" + notificationId);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            AcceptInvitationService.this.onDeleteNotification(notificationId);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            finishOperation(requestId);

            AcceptInvitationService.this.onSetCurrentSpace(space);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onDeleteDescriptors(long requestId, @NonNull Conversation conversation, @NonNull DescriptorId[] descriptorList) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteDescriptor: requestId=" + requestId
                        + " conversation=" + conversation + " descriptorList.length" + descriptorList.length);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            AcceptInvitationService.this.onDeleteDescriptor(descriptorList[0]);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwincodeOutboundServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            AcceptInvitationService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @Nullable
    private Observer mObserver;
    @Nullable
    private UUID mTwincodeOutboundId;
    @Nullable
    private final UUID mNotificationId;
    @Nullable
    private final UUID mGroupId;
    @Nullable
    private final UUID mContactId;
    @Nullable
    private final Uri mUri;
    private UUID mSpaceId;
    private ImageId mTwincodeAvatarId;
    private TwincodeOutbound mTwincodeOutbound;
    @Nullable
    private Conversation mConversation;
    @Nullable
    private DescriptorId mDescriptorId;
    private Profile mProfile;
    private int mWork = 0;
    @Nullable
    private Notification mNotification;
    @Nullable
    private Space mSpace;
    @Nullable
    private String mPublicKey;
    @Nullable
    private TrustMethod mTrustMethod;

    private int mState = 0;
    private final ConversationServiceObserver mConversationServiceObserver;

    public AcceptInvitationService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                                   @NonNull Observer observer, @Nullable Uri uri,
                                   @Nullable DescriptorId descriptorId, @Nullable UUID groupId,
                                   @Nullable UUID contactId, @Nullable UUID notificationId,
                                   @Nullable TrustMethod trustMethod) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "AcceptInvitationService: activity=" + activity + " twinmeContext=" + twinmeContext
                    + " observer=" + observer + " descriptorId=" + descriptorId + " groupId=" + groupId + " notificationId=" + notificationId);
        }

        mObserver = observer;
        mDescriptorId = descriptorId;
        mUri = uri;
        mGroupId = groupId;
        mContactId = contactId;
        mNotificationId = notificationId;
        mSpaceId = null;
        mTrustMethod = trustMethod;

        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
        showProgressIndicator();
    }

    public void createContact(@NonNull Profile profile, @NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createContact: profile=" + profile + " space=" + space);
        }

        mWork |= CREATE_CONTACT;
        mProfile = profile;
        mSpace = space;
        showProgressIndicator();
        startOperation();
    }

    public void deleteDescriptor(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteDescriptor: descriptorId=" + descriptorId);
        }

        mWork |= DELETE_DESCRIPTOR;
        mDescriptorId = descriptorId;
        showProgressIndicator();
        startOperation();
    }

    public void deleteNotification(@NonNull Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteNotification: deleteNotification=" + notification);
        }

        mWork |= DELETE_NOTIFICATION;
        mNotification = notification;
        showProgressIndicator();
        startOperation();
    }

    public void getSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: spaceId=" + spaceId);
        }

        mSpaceId = spaceId;
        mState &= ~(GET_SPACE | GET_SPACE_DONE);
        showProgressIndicator();
        startOperation();
    }

    @SuppressWarnings("unused")
    public void setCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: space= " + space);
        }

        long requestId = newOperation(SET_CURRENT_SPACE);
        if (DEBUG) {
            Log.d(LOG_TAG, "setCurrentSpace: requestId=" + requestId + " space= " + space);
        }

        showProgressIndicator();

        mTwinmeContext.setCurrentSpace(requestId, space);
        if (AppFlavor.TWINME_PLUS && !space.isSecret()) {
            mTwinmeContext.setDefaultSpace(space);
        }

    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mIsTwinlifeReady) {

            // Remove the peer twincode from our local cache:
            // - we want to force a fetch to the server the next time it is required,
            //   (so that we take into account a profile refresh by the peer)
            // - we don't need it in our database.
            // - when we evict the twincode, the associated avatar is also evicted.
            // - IFF the twincode is referenced, it is not and must not be evicted!
            if (mTwincodeOutbound != null) {
                mTwinmeContext.getTwincodeOutboundService().evictTwincodeOutbound(mTwincodeOutbound);
            }

            mTwinmeContext.getConversationService().removeServiceObserver(mConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);
    }

    protected void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        if (mRestarted) {
            mRestarted = false;

            if ((mState & GET_TWINCODE) != 0 && (mState & GET_TWINCODE_DONE) == 0) {
                mState &= ~GET_TWINCODE;
            }
            if ((mState & GET_TWINCODE_IMAGE) != 0 && (mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                mState &= ~GET_TWINCODE_IMAGE;
            }
        }
    }

    //
    // Private methods
    //

    @Override
    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: get the current space or the space identified by mSpaceId.
        //
        if ((mState & GET_SPACE) == 0) {
            mState |= GET_SPACE;

            if (mSpaceId != null) {
                mTwinmeContext.getSpace(mSpaceId, (ErrorCode errorCode, Space space) -> {
                    mSpace = space;
                    if (space != null) {
                        mProfile = space.getProfile();
                    }
                    runOnGetSpace(mObserver, space, null);
                    mState |= GET_SPACE_DONE;
                    onOperation();
                });
                return;

            } else {
                mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                    mSpace = space;
                    if (space != null) {
                        mProfile = space.getProfile();
                    }
                    runOnGetSpace(mObserver, space, null);
                    mState |= GET_SPACE_DONE;
                    onOperation();
                });
                return;
            }
        }
        if ((mState & GET_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2: parse the URI to build the TwincodeURI instance or get the invitation descriptor.
        //
        if (mUri != null) {
            if ((mState & PARSE_URI) == 0) {
                mState |= PARSE_URI;
                mTwinmeContext.getTwincodeOutboundService().parseURI(mUri, this::onParseURI);
                return;
            }
            if ((mState & PARSE_URI_DONE) == 0) {
                return;
            }

        } else if (mDescriptorId != null && ((mState & GET_DESCRIPTOR) == 0)) {
            mState |= GET_DESCRIPTOR;
            final Descriptor descriptor = mTwinmeContext.getConversationService().getDescriptor(mDescriptorId);
            if ((descriptor instanceof TwincodeDescriptor)) {
                TwincodeDescriptor twincodeDescriptor = (TwincodeDescriptor) descriptor;
                mTwincodeOutboundId = twincodeDescriptor.getTwincodeId();
                mPublicKey = twincodeDescriptor.getPublicKey();
            }
        }

        //
        // Step 2: find whether we have some contacts that were created by this same invitation.
        //
        if (mTwincodeOutboundId != null) {
            if ((mState & GET_EXISTING_CONTACTS) == 0) {
                mState |= GET_EXISTING_CONTACTS;

                if (DEBUG) {
                    Log.d(LOG_TAG, "findContacts: mTwincodeOutboundId=" + mTwincodeOutboundId);
                }
                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(null) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        final Contact contact = (Contact) object;
                        return mTwincodeOutboundId.equals(contact.getPublicPeerTwincodeOutboundId());
                    }
                };

                // Look for contacts matching the public peer twincode outbound in every space.
                mTwinmeContext.findContacts(filter, this::onExistingContacts);
                return;
            }

            if ((mState & GET_EXISTING_CONTACTS_DONE) == 0) {
                return;
            }
        }

        //
        // Step 3: get the twincode outbound.
        //
        if ((mState & GET_TWINCODE) == 0) {
            mState |= GET_TWINCODE;
            if (mTwincodeOutboundId == null) {
                mState |= GET_TWINCODE_DONE;
                runOnGetTwincodeNotFound(mObserver);

            } else {
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwincodeOutboundService.getTwincode: twincodeOutboundId=" + mTwincodeOutboundId);
                }
                if (mPublicKey == null) {
                    mTwinmeContext.getTwincodeOutboundService().getTwincode(mTwincodeOutboundId, TwincodeOutboundService.REFRESH_PERIOD,
                            this::onGetTwincodeOutbound);
                } else {
                    if (mTrustMethod == null) {
                        mTrustMethod = TrustMethod.AUTO;
                    }
                    mTwinmeContext.getTwincodeOutboundService().getSignedTwincode(mTwincodeOutboundId, mPublicKey,
                            mTrustMethod, this::onGetTwincodeOutbound);
                }
                return;
            }

            if ((mState & GET_TWINCODE_DONE) == 0) {
                return;
            }
        }

        //
        // Step 4: get the twincode avatar image.
        //
        if (mTwincodeAvatarId != null) {
            if ((mState & GET_TWINCODE_IMAGE) == 0) {
                mState |= GET_TWINCODE_IMAGE;

                if (DEBUG) {
                    Log.d(LOG_TAG, "ImageService.getImage: imageId=" + mTwincodeAvatarId);
                }
                mTwinmeContext.getImageService().getImageFromServer(mTwincodeAvatarId, ImageService.Kind.THUMBNAIL, (ErrorCode errorCode, Bitmap image) -> {
                    if (image != null) {
                        // Second call to onGetTwincode to give the twincode name with the image.
                        runOnGetTwincode(mObserver, mTwincodeOutbound, image);
                    }
                    mState |= GET_TWINCODE_IMAGE_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                return;
            }
        }

        //
        // Step 5a: get the optional group.
        //
        if (mGroupId != null) {
            if ((mState & GET_GROUP) == 0) {
                mState |= GET_GROUP;

                long requestId = newOperation(GET_GROUP);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.getGroup: requestId=" + requestId + " groupId=" + mGroupId);
                }

                mTwinmeContext.getGroup(mGroupId, this::onGetGroup);
                return;
            }

            if ((mState & GET_GROUP_DONE) == 0) {
                return;
            }
        }

        //
        // Step 5b: get the optional contact.
        //
        if (mContactId != null) {
            if ((mState & GET_CONTACT) == 0) {
                mState |= GET_CONTACT;

                mTwinmeContext.getContact(mContactId, this::onGetContact);
                return;
            }

            if ((mState & GET_CONTACT_DONE) == 0) {
                return;
            }
            if ((mState & GET_OR_CREATE_CONVERSATION_DONE) == 0) {
                return;
            }
        }

        //
        // Step 6: get the optional notification.
        //
        if (mNotificationId != null) {
            if ((mState & GET_NOTIFICATION) == 0) {
                mState |= GET_NOTIFICATION;

                if (DEBUG) {
                    Log.d(LOG_TAG, "getNotification: notificationId=" + mNotificationId);
                }

                mTwinmeContext.getNotification(mNotificationId, this::onGetNotification);
                return;
            }

            if ((mState & GET_NOTIFICATION_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: create the contact.
        //
        if (mProfile != null && mTwincodeOutbound != null && ((mWork & CREATE_CONTACT) != 0)) {
            if ((mState & CREATE_CONTACT) == 0) {
                mState |= CREATE_CONTACT;

                long requestId = newOperation(CREATE_CONTACT);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.createContactPhase1: requestId=" + requestId + " twincodeOutbound=" + mTwincodeOutbound +
                            " profile=" + mProfile);
                }

                mTwinmeContext.createContactPhase1(requestId, mTwincodeOutbound, mSpace, mProfile, null);
                return;
            }

            if ((mState & CREATE_CONTACT_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: delete the descriptor.
        //
        if (mConversation != null && mDescriptorId != null && ((mWork & DELETE_DESCRIPTOR) != 0)) {
            if ((mState & DELETE_DESCRIPTOR) == 0) {
                mState |= DELETE_DESCRIPTOR;

                long requestId = newOperation(DELETE_DESCRIPTOR);
                if (DEBUG) {
                    Log.d(LOG_TAG, "ConversationService.deleteDescriptor: requestId=" + requestId
                            + " mConversation=" + mConversation + " descriptorId=" + mDescriptorId);
                }

                mTwinmeContext.getConversationService().deleteDescriptor(requestId, mDescriptorId);
                return;
            }

            if ((mState & DELETE_DESCRIPTOR_DONE) == 0) {
                return;
            }
        }

        //
        // Work step: delete the notification.
        //
        if (mNotification != null && ((mWork & DELETE_NOTIFICATION) != 0)) {
            if ((mState & DELETE_NOTIFICATION) == 0) {
                mState |= DELETE_NOTIFICATION;

                long requestId = newOperation(DELETE_NOTIFICATION);
                if (DEBUG) {
                    Log.d(LOG_TAG, "deleteNotification: requestId=" + requestId + " notification=" + mNotification);
                }

                mTwinmeContext.deleteNotification(requestId, mNotification);
                return;
            }

            if ((mState & DELETE_NOTIFICATION_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    private void onCreateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateContact: contact=" + contact);
        }

        mState |= CREATE_CONTACT_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateContact(contact);
            }
        });
        onOperation();
    }

    private void onParseURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI twincodeUri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onParseURI: errorCode=" + errorCode + " twincodeUri=" + twincodeUri);
        }

        mState |= PARSE_URI_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onParseTwincodeURI(errorCode, twincodeUri);
            }
        });
        if (errorCode == ErrorCode.SUCCESS && twincodeUri != null) {
            mTwincodeOutboundId = twincodeUri.twincodeId;
            mPublicKey = twincodeUri.pubKey;
        }
        onOperation();
    }

    private void onGetTwincodeOutbound(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetTwincodeOutbound: twincodeOutbound=" + twincodeOutbound);
        }

        if (errorCode != ErrorCode.SUCCESS || twincodeOutbound == null) {

            onError(GET_TWINCODE, errorCode, null);
            return;
        }

        mState |= GET_TWINCODE_DONE;

        mTwinmeContext.assertEqual(ServiceAssertPoint.INVALID_TWINCODE, twincodeOutbound.getId(), mTwincodeOutboundId);

        // If the twincode is one of our twincode, report the error to avoid creating the contact.
        if (mTwinmeContext.isProfileTwincode(twincodeOutbound.getId())) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onLocalTwincode();
                }
            });
            return;
        }

        mTwincodeOutbound = twincodeOutbound;
        mTwincodeAvatarId = twincodeOutbound.getAvatarId();

        // First call to onGetTwincode to give the twincode name.
        runOnGetTwincode(mObserver, mTwincodeOutbound, null);
        onOperation();
    }

    private void onGetGroup(@NonNull ErrorCode errorCode, @Nullable Group group) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroup: group=" + group);
        }

        mState |= GET_GROUP_DONE;
        if (group != null) {

            if (group.getTwincodeOutboundId() != null) {
                mConversation = mTwinmeContext.getConversationService().getConversation(group);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            // Group not found means the invitation is invalid.
            runOnGetTwincodeNotFound(mObserver);
        } else {
            onError(GET_GROUP, errorCode, null);
        }
        onOperation();
    }

    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContact: contact=" + contact);
        }

        mState |= GET_CONTACT_DONE | GET_OR_CREATE_CONVERSATION | GET_OR_CREATE_CONVERSATION_DONE;

        if (contact != null) {
            mConversation = mTwinmeContext.getConversationService().getOrCreateConversation(contact);
            if (mConversation == null) {
                onError(GET_OR_CREATE_CONVERSATION, ErrorCode.ITEM_NOT_FOUND, null);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            // Contact not found means the invitation is invalid.
            runOnGetTwincodeNotFound(mObserver);
        }
        onOperation();
    }

    private void onExistingContacts(@NonNull List<Contact> list) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onExistingContacts: count=" + list.size());
        }

        mState |= GET_EXISTING_CONTACTS_DONE;
        if (!list.isEmpty()) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onExistingContacts(list);
                }
            });
        }
        onOperation();
    }

    private void onDeleteDescriptor(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor: descriptorId=" + descriptorId);
        }

        mState |= DELETE_DESCRIPTOR_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteDescriptor(descriptorId);
            }
        });
        onOperation();
    }

    private void onGetNotification(@NonNull ErrorCode errorCode, @Nullable Notification notification) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetNotification: notification=" + notification);
        }

        mState |= GET_NOTIFICATION_DONE;

        // Ignore a notification that was not found.
        if (errorCode == ErrorCode.SUCCESS && notification != null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetNotification(notification);
                }
            });
        }
        onOperation();
    }

    private void onDeleteNotification(@NonNull UUID notificationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteNotification: notificationId=" + notificationId);
        }

        mState |= DELETE_NOTIFICATION_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onDeleteNotification(notificationId);
            }
        });
        onOperation();
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        mState |= SET_CURRENT_SPACE_DONE;
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
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

        if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            switch (operationId) {
                case GET_TWINCODE:
                    mState |= GET_TWINCODE_DONE;
                    runOnGetTwincodeNotFound(mObserver);
                    return;

                case GET_GROUP:
                    mState |= GET_GROUP_DONE;

                    // Group not found means the invitation is invalid.
                    runOnGetTwincodeNotFound(mObserver);
                    return;

                case GET_CONTACT:
                    mState |= GET_CONTACT_DONE;

                    // Contact not found means the invitation is invalid.
                    runOnGetTwincodeNotFound(mObserver);
                    return;

                case CREATE_CONTACT:
                    mState |= CREATE_CONTACT_DONE;

                    // It can happen that the twincode becomes invalid when we create the contact.
                    runOnGetTwincodeNotFound(mObserver);
                    return;

                case DELETE_DESCRIPTOR:
                    mState |= DELETE_DESCRIPTOR_DONE;

                    // We can ignore deletion if the descriptor was not found.
                    runOnUiThread(() -> {
                        if (mObserver != null && mDescriptorId != null) {
                            mObserver.onDeleteDescriptor(mDescriptorId);
                        }
                    });

                    return;

                case DELETE_NOTIFICATION:
                    mState |= DELETE_NOTIFICATION_DONE;

                    // We can ignore deletion if the notification was not found.
                    runOnUiThread(() -> {
                        if (mObserver != null && mNotificationId != null) {
                            mObserver.onDeleteNotification(mNotificationId);
                        }
                    });

                    return;

                case GET_EXISTING_CONTACTS:
                    mState |= GET_EXISTING_CONTACTS_DONE;
                    return;

                default:
                    break;
            }

        } else if (errorCode == ErrorCode.BAD_REQUEST && operationId == CREATE_CONTACT) {
            mState |= CREATE_CONTACT_DONE;

            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onLocalTwincode();
                }
            });
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
