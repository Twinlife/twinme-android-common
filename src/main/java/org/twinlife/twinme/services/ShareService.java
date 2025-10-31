/*
 *  Copyright (c) 2019-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShareService extends AbstractTwinmeService {
    private static final String LOG_TAG = "ShareService";
    private static final boolean DEBUG = false;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_CONTACTS = 1 << 2;
    private static final int GET_CONTACTS_DONE = 1 << 3;
    private static final int GET_GROUPS = 1 << 4;
    private static final int GET_GROUPS_DONE = 1 << 5;
    private static final int PUSH_OBJECT = 1 << 7;
    private static final int GET_SPACE = 1 << 9;
    private static final int GET_SPACE_DONE = 1 << 10;
    private static final int FIND_CONTACTS_AND_GROUPS = 1 << 11;
    private static final int FIND_CONTACTS_AND_GROUPS_DONE = 1 << 12;
    private static final int GET_SPACES = 1 << 13;
    private static final int GET_SPACES_DONE = 1 << 14;
    private static final int GET_DESCRIPTOR = 1 << 15;
    private static final int PUSH_FILE = 1 << 16;

    public interface Observer extends AbstractTwinmeService.Observer, CurrentSpaceObserver, ContactListObserver,
            GroupListObserver, ContactObserver, SpaceObserver {

        void onGetConversation(@NonNull ConversationService.Conversation conversation);

        void onGetDescriptor(@Nullable ConversationService.Descriptor descriptor);

        void onSendFilesFinished();

        void onErrorNoPermission();
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            ShareService.this.onCreateContact(contact);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            ShareService.this.onUpdateContact(contact);
        }

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            ShareService.this.onDeleteContact(contactId);
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Contact contact, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " contact=" + contact + " oldSpace=" + oldSpace);
            }

            ShareService.this.onMoveContact(contact);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            ShareService.this.onSetCurrentSpace(space);
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onPushDescriptor(long requestId, @NonNull Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPushDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            final Integer operation = getOperation(requestId);
            ShareService.this.onPushDescriptor(operation, descriptor);
            onOperation();
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            ShareService.this.onError(operationId, errorCode, errorParameter);
            onOperation();
        }
    }

    @Nullable
    private ShareService.Observer mObserver;
    private Space mSpace;
    private final List<Space> mSpaces = new ArrayList<>();
    private int mState = 0;
    private int mWork = 0;
    private UUID mConversationId;
    @Nullable
    private Conversation mConversation;
    @Nullable
    private String mFindName;
    private final DescriptorId mForwardDescriptorId;
    private final ConversationServiceObserver mConversationServiceObserver;
    @Nullable
    private List<FileInfo> mFiles;
    @Nullable
    private FileInfo mCurrentFile;

    public ShareService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext,
                        @NonNull ShareService.Observer observer, @Nullable DescriptorId descriptorId) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ContactsService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mForwardDescriptorId = descriptorId;
        mTwinmeContextObserver = new ShareService.TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void getSpace(@NonNull UUID spaceId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getSpace: spaceId=" + spaceId);
        }

        showProgressIndicator();

        mTwinmeContext.getSpace(spaceId, (ErrorCode errorCode, Space space) -> {

            runOnGetSpace(mObserver, space, null);
            mState |= GET_SPACE_DONE;
            mSpace = space;
            mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE | GET_GROUPS | GET_GROUPS_DONE);
            onOperation();
        });
    }

    public void getConversation(@NonNull Originator subject) {

        mTwinmeContext.execute(() -> {
            if (subject instanceof Contact) {
                mConversation = mTwinmeContext.getConversationService().getOrCreateConversation(subject);
            } else if (subject instanceof Group) {
                mConversation = mTwinmeContext.getConversationService().getConversation(subject);
            }
            if (mConversation != null) {
                mConversationId = mConversation.getId();
                runOnGetConversation(mObserver, mConversation);
            }
        });
    }

    public void pushMessage(String message, boolean copyAllowed) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushMessage: message=" + message + " copyAllowed=" + copyAllowed);
        }

        if (mConversation == null) {

            return;
        }
        long requestId = newOperation(PUSH_OBJECT);
        if (DEBUG) {
            Log.d(LOG_TAG, "ConversationService.pushObject: requestId=" + requestId + " conversationId=" + mConversationId + " message=" + message + " copyAllowed=" + copyAllowed);
        }
        mTwinmeContext.pushMessage(requestId, mConversation, null, null, message, copyAllowed, 0);
    }

    public void pushFile(@NonNull Uri file, @NonNull String filename, @NonNull ConversationService.Descriptor.Type type, boolean toDelete, boolean allowCopy,
                         @Nullable UUID sendTo, @Nullable DescriptorId replyTo, long expireTimeout) {
        if (DEBUG) {
            Log.d(LOG_TAG, "pushFile: file=" + file);
        }

        synchronized (this) {
            if (mFiles == null) {
                mFiles = new ArrayList<>();
            }
            mFiles.add(new FileInfo(file, filename, type, toDelete, allowCopy, sendTo, replyTo, expireTimeout));
        }
        startOperation();
    }

    public synchronized boolean isSendingFiles() {

        return mFiles != null && (!mFiles.isEmpty() || mCurrentFile != null);
    }

    public void forwardDescriptor(@NonNull DescriptorId descriptorId, boolean copyAllowed) {
        if (DEBUG) {
            Log.d(LOG_TAG, "forwardDescriptor: descriptorId=" + descriptorId + " copyAllowed=" + copyAllowed);
        }

        long requestId = newOperation(PUSH_OBJECT);
        if (DEBUG) {
            Log.d(LOG_TAG, "ConversationService.forwardDescriptor: requestId=" + requestId + " conversationId=" + mConversationId
                    + " descriptorId=" + descriptorId + " copyAllowed=" + copyAllowed);
        }
        mTwinmeContext.forwardDescriptor(requestId, mConversation, null, descriptorId, copyAllowed, 0);
    }

    public void findContactsAndGroupsByName(@NonNull String name) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactsAndGroupsByName: name=" + name);
        }

        showProgressIndicator();
        mWork |= FIND_CONTACTS_AND_GROUPS;
        mState &= ~(FIND_CONTACTS_AND_GROUPS | FIND_CONTACTS_AND_GROUPS_DONE);
        mFindName = normalize(name);

        startOperation();
    }

    public int numberSpaces(boolean countSecretSpace) {
        if (DEBUG) {
            Log.d(LOG_TAG, "numberSpaces: " + countSecretSpace);
        }

        if (countSecretSpace) {
            return mSpaces.size();
        }

        int count = 0;
        for (Space space : mSpaces) {
            if (!space.getSpaceSettings().isSecret()) {
                count++;
            }
        }

        return count;
    }

    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getConversationService().removeServiceObserver(mConversationServiceObserver);
        }

        mObserver = null;
        super.dispose();
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        if (mSpace != space) {
            mSpace = space;
            mState &= ~(GET_CONTACTS | GET_CONTACTS_DONE | GET_GROUPS | GET_GROUPS_DONE);
        }
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);
    }

    private void onPushDescriptor(@Nullable Integer operationId, @NonNull ConversationService.Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPushDescriptor: descriptor=" + descriptor);
        }

        // If this was a push file, handle the next file or notify we are done.
        if (operationId != null && operationId == PUSH_FILE) {
            nextPushFile();
        }
    }

    private void onCreateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateContact: contact=" + contact);
        }
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        if (contact.getSpace() == mSpace) {
            runOnUpdateContact(mObserver, contact, null);
        }
    }

    private void onMoveContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveContact: contact=" + contact);
        }

        if (contact.getSpace() != mSpace) {
            runOnDeleteContact(mObserver, contact.getId());
        }
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        runOnDeleteContact(mObserver, contactId);
    }

    private void nextPushFile() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextPushFile");
        }

        synchronized (this) {
            mCurrentFile = null;
            if (mFiles != null && !mFiles.isEmpty()) {
                mCurrentFile = mFiles.remove(0);
                mState &= ~PUSH_FILE;
            }
        }
        if (mCurrentFile == null) {
            runOnUiThread(() -> {
                if (mObserver != null && mFiles.isEmpty()) {
                    mObserver.onSendFilesFinished();
                }
            });
        }
    }

    protected void onOperation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mIsTwinlifeReady) {

            return;
        }

        //
        // Step 1: get spaces.
        //

        if ((mState & GET_SPACES) == 0) {
            mState |= GET_SPACES;

            mTwinmeContext.findSpaces((Space space) -> true, (ErrorCode errorCode, List<Space> spaces) -> {
                mSpaces.clear();
                if (spaces != null) {
                    mSpaces.addAll(spaces);
                }
                mState |= GET_SPACES_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_SPACES_DONE) == 0) {
            return;
        }

        //
        // Step 2: Get the current space.
        //
        if ((mState & GET_CURRENT_SPACE) == 0) {
            mState |= GET_CURRENT_SPACE;

            mTwinmeContext.getCurrentSpace((ErrorCode errorCode, Space space) -> {
                runOnGetSpace(mObserver, space, null);
                mState |= GET_CURRENT_SPACE_DONE;
                mSpace = space;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 3: If there is a descriptor to forward, get it from the ConversationService.
        //
        if (mForwardDescriptorId != null && (mState & GET_DESCRIPTOR) == 0) {
            mState |= GET_DESCRIPTOR;
            ConversationService.Descriptor descriptor = mTwinmeContext.getConversationService().getDescriptor(mForwardDescriptorId);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDescriptor(descriptor);
                }
            });
        }

        //
        // Step 3a: We must get the list of contacts for the space, we exclude deleted contacts.
        //
        if ((mState & GET_CONTACTS) == 0) {
            mState |= GET_CONTACTS;

            final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                public boolean accept(@NonNull RepositoryObject object) {
                    if (!(object instanceof Contact)) {
                        return false;
                    }

                    final Contact contact = (Contact) object;
                    return contact.hasPeer();
                }
            };
            mTwinmeContext.findContacts(filter, (List<Contact> contacts) -> {
                runOnGetContacts(mObserver, contacts);
                mState |= GET_CONTACTS_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CONTACTS_DONE) == 0) {
            return;
        }

        //
        // Step 3b: We must get the list of groups for the space.
        //
        if ((mState & GET_GROUPS) == 0) {
            mState |= GET_GROUPS;

            final Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                public boolean accept(@NonNull RepositoryObject object) {
                    if (!(object instanceof Group)) {
                        return false;
                    }

                    final Group group = (Group) object;
                    return !group.isLeaving();
                }
            };

            mTwinmeContext.findGroups(filter, (List<Group> groups) -> {
                runOnGetGroups(mObserver, groups);
                mState |= GET_GROUPS_DONE;
                onOperation();
            });
            return;
        }
        if ((mState & GET_GROUPS_DONE) == 0) {
            return;
        }

        //
        // We must get the list of contacts and groups by name.
        //
        if ((mWork & FIND_CONTACTS_AND_GROUPS) != 0 && mFindName != null) {
            if ((mState & FIND_CONTACTS_AND_GROUPS) == 0) {
                mState |= FIND_CONTACTS_AND_GROUPS;

                final Filter<RepositoryObject> contactFilter = new Filter<RepositoryObject>(mSpace) {
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Contact)) {
                            return false;
                        }

                        final Contact contact = (Contact) object;
                        String contactName = normalize(object.getName());
                        return contact.hasPeer() && contactName.contains(mFindName);
                    }
                };

                mTwinmeContext.findContacts(contactFilter, (List<Contact> contacts) -> {
                    runOnGetContacts(mObserver, contacts);
                    mState |= FIND_CONTACTS_AND_GROUPS;
                    onOperation();
                });

                final Filter<RepositoryObject> groupFilter = new Filter<RepositoryObject>(mSpace) {
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Group)) {
                            return false;
                        }

                        final Group group = (Group) object;
                        String groupName = normalize(group.getName());
                        return !group.isLeaving() && groupName.contains(mFindName);
                    }
                };

                mTwinmeContext.findGroups(groupFilter, (List<Group> groups) -> {
                    runOnGetGroups(mObserver, groups);
                    mState |= FIND_CONTACTS_AND_GROUPS_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & FIND_CONTACTS_AND_GROUPS_DONE) == 0) {
                return;
            }
        }

        // Handle sending files.
        if (mFiles != null && mConversation != null) {
            if (mCurrentFile == null) {
                nextPushFile();
            }
            if (mCurrentFile != null) {
                if ((mState & PUSH_FILE) == 0) {
                    mState |= PUSH_FILE;

                    long requestId = newOperation(PUSH_FILE);
                    mTwinmeContext.getConversationService().pushFile(requestId, mConversation, mCurrentFile.sendTo, mCurrentFile.replyTo,
                            mCurrentFile.file, mCurrentFile.filename, mCurrentFile.type, mCurrentFile.toDelete, mCurrentFile.allowCopy,
                            mCurrentFile.expireTimeout * 1000);
                }
                return;
            }
        }

        hideProgressIndicator();
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

        // If this is a push file, we are done with the current file.
        if (operationId == PUSH_FILE) {
            super.onError(operationId, errorCode, errorParameter);
            nextPushFile();
        } else {
            if (errorCode == ErrorCode.NO_PERMISSION) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onErrorNoPermission();
                    }
                });
                return;
            }

            super.onError(operationId, errorCode, errorParameter);
        }
    }
}
