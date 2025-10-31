/*
 *  Copyright (c) 2021-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeOutboundService;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinlife.util.EventMonitor;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Invitation;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.List;
import java.util.UUID;

public class InvitationRoomService extends AbstractTwinmeService {
    private static final String LOG_TAG = "InvitationRoomService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_ROOM = 1 << 2;
    private static final int GET_ROOM_DONE = 1 << 3;
    private static final int GET_TWINCODE = 1 << 4;
    private static final int GET_TWINCODE_DONE = 1 << 5;
    private static final int GET_INVITATION_LINK = 1 << 6;
    private static final int GET_INVITATION_LINK_DONE = 1 << 7;
    private static final int GET_CONTACTS = 1 << 8;
    private static final int GET_CONTACTS_DONE = 1 << 9;
    private static final int FIND_CONTACTS = 1 << 10;
    private static final int FIND_CONTACTS_DONE = 1 << 11;
    private static final int PUSH_TWINCODE = 1 << 12;

    public interface Observer extends ShowContactService.Observer, AbstractTwinmeService.ContactListObserver /*, AbstractTwinmeService.ContactObserver */ {

        void onGetTwincodeURI(@NonNull TwincodeURI uri);

        void onSendTwincodeToContacts();
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onPushDescriptor(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPushDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            InvitationRoomService.this.onPushTwincode();
        }
    }

    @Nullable
    private Observer mObserver;
    private final UUID mRoomId;
    private Contact mRoom;
    private int mState = 0;
    private int mWork = 0;
    private final ConversationServiceObserver mConversationServiceObserver;
    private ConversationService mConversationService;
    private List<Contact> mContactsToInvite;
    private Contact mCurrentContact;
    private Space mSpace;
    @Nullable
    private Conversation mConversation;
    @Nullable
    private TwincodeOutbound mTwincodeOutbound;

    @Nullable
    private String mFindName;

    public InvitationRoomService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer, @NonNull UUID roomId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "GroupService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mRoomId = roomId;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();
        mConversationService = mTwinmeContext.getConversationService();
        mConversationService.addServiceObserver(mConversationServiceObserver);
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
        }
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife() && mConversationService != null) {
            mConversationService.removeServiceObserver(mConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    public void getContacts() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getContacts");
        }

        mWork |= GET_CONTACTS;
        mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE);
        showProgressIndicator();
        startOperation();
    }

    public void findContactsByName(@NonNull String name) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactsByName: name=" + name);
        }

        showProgressIndicator();
        mWork |= FIND_CONTACTS;
        mState &= ~(FIND_CONTACTS | FIND_CONTACTS_DONE);
        mFindName = normalize(name);

        startOperation();
    }

    public void inviteContactToRoom(@NonNull List<Contact> contactsToInvite, Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "inviteContactToRoom: contactsToInvite=" + contactsToInvite);
        }

        showProgressIndicator();
        mContactsToInvite = contactsToInvite;
        mRoom = room;

        mCurrentContact = mContactsToInvite.get(0);

        getConversationWithCurrentContact();
    }

    private void getConversationWithCurrentContact() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getConversationWithCurrentContact");
        }

        if (mCurrentContact.getTwincodeOutboundId() == null || mCurrentContact.getPeerTwincodeOutboundId() == null || mCurrentContact.getTwincodeInboundId() == null) {
            return;
        }
        mConversation = mTwinmeContext.getConversationService().getOrCreateConversation(mCurrentContact);
        if (mConversation != null) {
            pushTwincode();
        }
    }

    private void pushTwincode() {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushTwincode");
        }

        if (mConversation == null || mRoom.getPublicPeerTwincodeOutboundId() == null) {

            return;
        }

        long requestId = newOperation(PUSH_TWINCODE);
        mTwinmeContext.pushTwincode(requestId, mConversation, null, null, mRoom.getPublicPeerTwincodeOutboundId(), Invitation.SCHEMA_ID, null, true, 0);
    }

    private void onGetContacts(@NonNull List<Contact> contacts) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContacts");
        }

        EventMonitor.info(LOG_TAG, "Found ", contacts.size(), " contacts");

        mState |= GET_CONTACTS_DONE;
        runOnGetContacts(mObserver, contacts);
        onOperation();
    }

    private void onGetCurrentSpace(@NonNull ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;
        mSpace = space;
        onOperation();
    }

    private void onGetRoom(@NonNull ErrorCode errorCode, @Nullable Contact room) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetRoom: room=" + room);
        }

        mState |= GET_ROOM_DONE;
        mRoom = room;
        if (room != null) {

            Bitmap avatar = getImage(room);
            runOnGetContact(mObserver, room, avatar);
            if (avatar == null && room.getAvatarId() != null) {
                getImageFromServer(room);
            }
            if (mRoom.getPublicPeerTwincodeOutboundId() != null) {
                mWork |= GET_TWINCODE;
                mState &= ~(GET_TWINCODE | GET_TWINCODE_DONE);
            }
        } else if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            runOnGetContactNotFound(mObserver);
        } else {
            onError(GET_ROOM, errorCode, null);
        }
        onOperation();
    }

    private void onGetTwincode(@NonNull ErrorCode errorCode, @Nullable TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetTwincode: errorCode=" + errorCode + " twincodeOutbound=" + twincodeOutbound);
        }

        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {
            mRestarted = true;
            return;
        }

        mState |= GET_TWINCODE_DONE;
        mTwincodeOutbound = twincodeOutbound;
        if (twincodeOutbound != null) {
            mWork |= GET_INVITATION_LINK;
            mState &= ~(GET_INVITATION_LINK | GET_INVITATION_LINK_DONE);
        }
        onOperation();
    }

    private void onCreateURI(@NonNull ErrorCode errorCode, @Nullable TwincodeURI uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateURI: errorCode=" + errorCode + " uri=" + uri);
        }

        mState |= GET_INVITATION_LINK_DONE;
        if (uri != null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetTwincodeURI(uri);
                }
            });
        }
        onOperation();
    }

    private void onPushTwincode() {

        if (mContactsToInvite != null && !mContactsToInvite.isEmpty()) {
            mContactsToInvite.remove(0);

            if (!mContactsToInvite.isEmpty()) {
                mCurrentContact = mContactsToInvite.get(0);
                getConversationWithCurrentContact();
            } else {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onSendTwincodeToContacts();
                    }
                });
            }
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

        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            long requestId = newOperation(GET_CURRENT_SPACE);
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.getCurrentSpace: requestId=" + requestId);
            }

            mTwinmeContext.getCurrentSpace(this::onGetCurrentSpace);
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 1: Get the current room.
        //
        if ((mState & GET_ROOM) == 0) {
            mState |= GET_ROOM;

            mTwinmeContext.getContact(mRoomId, this::onGetRoom);
            return;
        }
        if ((mState & GET_ROOM_DONE) == 0) {
            return;
        }

        if (mRoom != null && mRoom.getPublicPeerTwincodeOutboundId() != null) {
            if ((mState & GET_TWINCODE) == 0) {
                mState |= GET_TWINCODE;

                mTwinmeContext.getTwincodeOutboundService().getTwincode(mRoom.getPublicPeerTwincodeOutboundId(),
                        TwincodeOutboundService.REFRESH_PERIOD, this::onGetTwincode);
                return;
            }
            if ((mState & GET_TWINCODE_DONE) == 0) {
                return;
            }
        }

        if (mTwincodeOutbound != null && (mWork & GET_INVITATION_LINK) != 0) {
            if ((mState & GET_INVITATION_LINK) == 0) {
                mState |= GET_INVITATION_LINK;
                mTwinmeContext.getTwincodeOutboundService().createURI(TwincodeURI.Kind.Invitation, mTwincodeOutbound,
                        this::onCreateURI);
                return;
            }
            if ((mState & GET_INVITATION_LINK_DONE) == 0) {
                return;
            }
        }

        // We must get the list of contacts.
        if ((mWork & GET_CONTACTS) != 0) {
            if ((mState & GET_CONTACTS) == 0) {
                mState |= GET_CONTACTS;
                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Contact)) {
                            return false;
                        }

                        final Contact contact = (Contact) object;
                        return !contact.isTwinroom() && contact.hasPeer();
                    }
                };
                mTwinmeContext.findContacts(filter, this::onGetContacts);
                return;
            }
            if ((mState & GET_CONTACTS_DONE) == 0) {
                return;
            }
        }

        //
        // We must get the list of contacts by name.
        //
        if ((mWork & FIND_CONTACTS) != 0 && mFindName != null) {
            if ((mState & FIND_CONTACTS) == 0) {
                mState |= FIND_CONTACTS;

                /* TwinmeContext.Predicate<Contact> filter = (Contact contact) -> {
                    if (contact.getName() == null) {
                        return false;
                    }
                    String contactName = normalize(contact.getName());
                    return mTwinmeContext.isCurrentSpace(contact) && contactName.contains(mFindName) && !contact.isTwinroom() && contact.hasPeer();
                };*/
                final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Contact)) {
                            return false;
                        }

                        final Contact contact = (Contact) object;
                        return !contact.isTwinroom() && contact.hasPeer();
                    }
                };
                filter.withName(mFindName);
                mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onGetContacts(contacts);
                        }
                    });
                    mState |= FIND_CONTACTS_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & FIND_CONTACTS_DONE) == 0) {
                return;
            }
        }

        // Nothing more to do, we can hide the progress indicator.
        hideProgressIndicator();
    }
}
