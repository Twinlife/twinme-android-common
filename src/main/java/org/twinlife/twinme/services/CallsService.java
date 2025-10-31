/*
 *  Copyright (c) 2020-2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.CallDescriptor;
import org.twinlife.twinlife.ConversationService.ClearMode;
import org.twinlife.twinlife.ConversationService.Conversation;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.UpdateType;
import org.twinlife.twinlife.DisplayCallsMode;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CallsService extends AbstractTwinmeService {
    private static final String LOG_TAG = "CallsService";
    private static final boolean DEBUG = false;

    private static final int MAX_OBJECTS = 30;

    private static final int GET_CURRENT_SPACE = 1;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 1;
    private static final int GET_ORIGINATOR = 1 << 2;
    private static final int GET_ORIGINATOR_DONE = 1 << 3;
    private static final int GET_CONTACTS = 1 << 4;
    private static final int GET_CONTACTS_DONE = 1 << 5;
    private static final int GET_DESCRIPTORS = 1 << 6;
    private static final int GET_DESCRIPTORS_DONE = 1 << 7;
    private static final int DELETE_DESCRIPTOR = 1 << 8;
    private static final int DELETE_DESCRIPTOR_DONE = 1 << 9;
    private static final int GET_CALL_RECEIVERS = 1 << 10;
    private static final int GET_CALL_RECEIVERS_DONE = 1 << 11;
    private static final int GET_GROUPS = 1 << 12;
    private static final int GET_GROUPS_DONE = 1 << 13;
    private static final int DELETE_CALL_RECEIVER = 1 << 14;
    private static final int DELETE_CALL_RECEIVER_DONE = 1 << 15;
    private static final int GET_GROUP_MEMBERS = 1 << 16;
    private static final int GET_GROUP_MEMBERS_DONE = 1 << 17;
    private static final int COUNT_CALL_RECEIVERS = 1 << 18;
    private static final int COUNT_CALL_RECEIVERS_DONE = 1 << 19;

    public interface Observer extends AbstractTwinmeService.Observer, CurrentSpaceObserver, ContactListObserver,
            ContactObserver, GroupListObserver, GroupObserver {

        void onCreateContact(@NonNull Contact contact, @Nullable Bitmap avatar);

        void onUpdateContact(@NonNull Contact contact, @Nullable Bitmap avatar);

        void onGetDescriptors(@NonNull List<CallDescriptor> descriptors);

        void onAddDescriptor(@NonNull CallDescriptor descriptor);

        void onUpdateDescriptor(@NonNull CallDescriptor descriptor);

        void onDeleteDescriptors(@NonNull Set<DescriptorId> descriptorList);

        void onResetConversation(@NonNull Conversation conversation, @NonNull ClearMode clearMode);

        void onGetCallReceivers(@NonNull List<CallReceiver> callReceivers);

        default void onGetGroups(@NonNull List<Group> groups) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Observer.onGetGroups: groups.size=" + groups.size());
            }
        }

        void onGetCallReceiver(@NonNull CallReceiver callReceiver, @Nullable Bitmap avatar);

        void onCreateCallReceiver(@NonNull CallReceiver callReceiver);

        void onUpdateCallReceiver(@NonNull CallReceiver callReceiver);

        void onDeleteCallReceiver(@NonNull UUID callReceiverId);

        void onGetGroupMembers(@NonNull List<ConversationService.GroupMemberConversation> groupMembers);

        default void onGetCountCallReceivers(int countCallReceivers) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Observer.onGetCountCallReceivers: " + countCallReceivers);
            }
        }
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            CallsService.this.onCreateContact(contact);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            CallsService.this.onUpdateContact(contact);
        }

        @Override
        public void onMoveToSpace(long requestId, @NonNull Contact contact, @NonNull Space oldSpace) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onMoveToSpace: requestId=" + requestId + " contact=" + contact + " oldSpace=" + oldSpace);
            }

            CallsService.this.onMoveContact(contact);
        }

        @Override
        public void onDeleteContact(long requestId, @NonNull UUID contactId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteContact: requestId=" + requestId + " contactId=" + contactId);
            }

            CallsService.this.onDeleteContact(contactId);
        }

        @Override
        public void onCreateCallReceiver(long requestId, @NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateCallReceiver: requestId=" + requestId + " callReceiver=" + callReceiver);
            }

            CallsService.this.onCreateCallReceiver(callReceiver);
        }

        @Override
        public void onUpdateCallReceiver(long requestId, @NonNull CallReceiver callReceiver) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateCallReceiver: requestId=" + requestId + " callReceiver=" + callReceiver);
            }

            CallsService.this.onUpdateCallReceiver(callReceiver);
        }

        @Override
        public void onDeleteCallReceiver(long requestId, @NonNull UUID callReceiverId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDeleteCallReceiver: requestId=" + requestId + " callReceiverId=" + callReceiverId);
            }

            CallsService.this.onDeleteCallReceiver(callReceiverId);
        }

        @Override
        public void onSetCurrentSpace(long requestId, @NonNull Space space) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onSetCurrentSpace: requestId=" + requestId + " space=" + space);
            }

            CallsService.this.onSetCurrentSpace(space);
        }
    }

    private class ConversationServiceObserver extends org.twinlife.twinlife.ConversationService.DefaultServiceObserver {

        @Override
        public void onResetConversation(@NonNull Conversation conversation, @NonNull ClearMode clearMode) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onResetConversation: conversation=" + conversation);
            }

            CallsService.this.onResetConversation(conversation, clearMode);
        }

        @Override
        public void onPushDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPushDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (descriptor.getType() != Descriptor.Type.CALL_DESCRIPTOR) {

                return;
            }

            CallsService.this.onPushDescriptor((CallDescriptor) descriptor);
            onOperation();
        }

        @Override
        public void onPopDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onPopDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor);
            }

            if (descriptor.getType() != Descriptor.Type.CALL_DESCRIPTOR) {

                return;
            }

            CallsService.this.onPopDescriptor((CallDescriptor) descriptor);
        }

        @Override
        public void onUpdateDescriptor(long requestId, @NonNull Conversation conversation, @NonNull Descriptor descriptor, UpdateType updateType) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onUpdateDescriptor: requestId=" + requestId + " conversation=" + conversation + " descriptor=" + descriptor +
                        " updateType=" + updateType);
            }

            if (descriptor.getType() != Descriptor.Type.CALL_DESCRIPTOR) {

                return;
            }

            CallsService.this.onUpdateDescriptor((CallDescriptor) descriptor);
        }

        @Override
        public void onDeleteDescriptors(long requestId, @NonNull Conversation conversation, @NonNull DescriptorId[] descriptorList) {
            if (DEBUG) {
                Log.d(LOG_TAG, "ConversationServiceObserver.onDeleteDescriptor: requestId=" + requestId
                        + " conversation=" + conversation + " descriptorList=" + descriptorList);
            }

            finishOperation(requestId);
            CallsService.this.onDeleteDescriptors(descriptorList);
        }
    }

    @Nullable
    private Observer mObserver;
    private int mWork = 0;
    private int mState = 0;
    private long mBeforeTimestamp = Long.MAX_VALUE;
    private boolean mGetDescriptorsDone = false;
    private Space mSpace;
    @Nullable
    private Conversation mConversation;
    @Nullable
    private final UUID mOriginatorId;
    @Nullable
    private Originator mGroup;
    @Nullable
    private final Originator.Type mOriginatorType;
    private final Map<UUID, Originator> mOriginators;
    private final Set<UUID> mOriginatorTwincodes;
    @Nullable
    private CallReceiver mCallReceiver;
    private final ConversationServiceObserver mConversationServiceObserver;

    public CallsService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull Observer observer, @Nullable UUID originatorId, @Nullable Originator.Type originatorType) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "ContactsService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;
        mTwinmeContextObserver = new TwinmeContextObserver();
        mConversationServiceObserver = new ConversationServiceObserver();
        mOriginators = new HashMap<>();
        mOriginatorTwincodes = new HashSet<>();
        mOriginatorId = originatorId;
        mOriginatorType = originatorType;

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    @Override
    protected void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        super.onTwinlifeReady();

        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);
    }

    @Override
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

    public void getPreviousDescriptors() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPreviousDescriptors");
        }

        if (mGetDescriptorsDone) {

            return;
        }

        mState &= ~(GET_DESCRIPTORS | GET_DESCRIPTORS_DONE);
        startOperation();
    }

    public void deleteCallDescriptor(@NonNull CallDescriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteCallDescriptor: descriptor=" + descriptor);
        }

        long requestId = newOperation(DELETE_DESCRIPTOR);
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteDescriptor: requestId=" + requestId + " descriptor=" + descriptor);
        }
        showProgressIndicator();

        mTwinmeContext.deleteDescriptor(requestId, descriptor.getDescriptorId());
    }

    public boolean isGetDescriptorDone() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isGetDescriptorDone");
        }

        return mGetDescriptorsDone;
    }

    /**
     * <p>
     * Delete a {@link CallReceiver}.
     * <p>
     * Once deleted the {@link CallReceiverService.Observer#onDeleteCallReceiver(UUID)} callback will be called with the CallReceiver's ID.
     * <p>
     * An alternative is to implement the {@link TwinmeContext.Observer#onDeleteCallReceiver(long, UUID)} callback to
     * monitor CallReceivers deletions.
     *
     * @param callReceiver the CallReceiver to delete
     */
    public void deleteCallReceiver(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteCallReceiver: callReceiver=" + callReceiver);
        }

        mCallReceiver = callReceiver;

        mWork |= DELETE_CALL_RECEIVER;
        mState &= ~(DELETE_CALL_RECEIVER | DELETE_CALL_RECEIVER_DONE);

        startOperation();
    }

    public void countCallReceivers() {
        if (DEBUG) {
            Log.d(LOG_TAG, "countCallReceivers");
        }

        mWork |= COUNT_CALL_RECEIVERS;
        mState &= ~(COUNT_CALL_RECEIVERS | COUNT_CALL_RECEIVERS_DONE);

        startOperation();
    }

    public void getGroupMembers(@NonNull Originator originator) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getGroupMembers: originator=" + originator);
        }

        mGroup = originator;

        mWork |= GET_GROUP_MEMBERS;
        mState &= ~(GET_GROUP_MEMBERS | GET_GROUP_MEMBERS_DONE);

        startOperation();
    }

    @Override
    protected void onSetCurrentSpace(@NonNull Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onSetCurrentSpace: space=" + space);
        }

        mSpace = space;
        mState = GET_CURRENT_SPACE | GET_CURRENT_SPACE_DONE;
        mGetDescriptorsDone = false;
        mBeforeTimestamp = Long.MAX_VALUE;
        runOnSetCurrentSpace(mObserver, space);
        onOperation();
    }

    private void onGetContacts(@NonNull List<Contact> contacts) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetContacts: contacts=" + contacts);
        }

        mState |= GET_CONTACTS_DONE;

        removeOriginatorsByType(Originator.Type.CONTACT);

        mOriginatorTwincodes.clear();
        for (Contact contact : contacts) {
            mOriginators.put(contact.getId(), contact);
            mOriginatorTwincodes.add(contact.getTwincodeOutboundId());
        }
        runOnGetContacts(mObserver, contacts);
        onOperation();
    }

    private void onGetOriginator(@NonNull ErrorCode errorCode, @Nullable Originator originator) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateOriginator: originator=" + originator);
        }

        mState |= GET_ORIGINATOR_DONE;
        mOriginators.clear();
        mOriginatorTwincodes.clear();
        if (originator != null) {

            UUID twincodeOutboundId = originator.getTwincodeOutboundId();
            mOriginators.put(originator.getId(), originator);

            Bitmap avatar = getImage(originator);

            if (originator.getType() == Originator.Type.CONTACT) {
                runOnGetContact(mObserver, (Contact) originator, avatar);
                if (avatar == null && originator.getAvatarId() != null) {
                    getImageFromServer(originator);
                }
            } else if (originator.getType() == Originator.Type.GROUP) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onGetGroup((Group) originator, avatar);
                    }
                });
            } else if (originator.getType() == Originator.Type.CALL_RECEIVER) {
                runOnUiThread(() -> {
                    if (mObserver != null) {
                        mObserver.onGetCallReceiver((CallReceiver) originator, avatar);
                    }
                });
            }
            if (twincodeOutboundId != null) {
                mOriginatorTwincodes.add(twincodeOutboundId);

                mConversation = mTwinmeContext.getConversationService().getConversation(originator);
            }
        }
        onOperation();
    }

    private void onCreateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateContact: contact=" + contact);
        }

        if (contact.getSpace() == mSpace) {
            mOriginators.put(contact.getId(), contact);
            mOriginatorTwincodes.add(contact.getTwincodeOutboundId());
            Bitmap avatar = getImage(contact);
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onCreateContact(contact, avatar);
                }
            });
            if (avatar == null && contact.getAvatarId() != null) {
                getImageFromServer(contact);
            }
        }
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        if (contact.getSpace() == mSpace) {
            Bitmap avatar = getImage(contact);
            runOnUpdateContact(mObserver, contact, avatar);
            if (avatar == null && contact.getAvatarId() != null) {
                getImageFromServer(contact);
            }
        }
    }

    private void onMoveContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMoveContact: contact=" + contact);
        }

        if (contact.getSpace() != mSpace) {
            mOriginators.remove(contact.getId());
            mOriginatorTwincodes.remove(contact.getTwincodeOutboundId());
            runOnDeleteContact(mObserver, contact.getId());
        }
    }

    private void onDeleteContact(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteContact: contactId=" + contactId);
        }

        Originator contact = mOriginators.remove(contactId);
        if (contact instanceof Contact) {
            mOriginatorTwincodes.remove(contact.getTwincodeOutboundId());
            runOnDeleteContact(mObserver, contactId);
        }
    }

    private void onDeleteCallReceiver(@NonNull UUID callReceiverId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteCallReceiver: callReceiverId=" + callReceiverId);
        }

        Originator callReceiver = mOriginators.remove(callReceiverId);
        if (callReceiver instanceof CallReceiver) {
            mOriginatorTwincodes.remove(callReceiver.getTwincodeOutboundId());
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onDeleteCallReceiver(callReceiverId);
                }
            });
        }
    }

    private void onGetGroupMembersConversations(@NonNull List<ConversationService.GroupMemberConversation> groupMemberConversations) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroupMembersConversations: groupMemberConversations=" + groupMemberConversations);
        }

        mState |= GET_GROUP_MEMBERS_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetGroupMembers(groupMemberConversations);
            }
        });
    }

    private void onCreateCallReceiver(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateCallReceiver: callReceiver=" + callReceiver);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateCallReceiver(callReceiver);
            }
        });
    }

    private void onUpdateCallReceiver(@NonNull CallReceiver callReceiver) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateCallReceiver: callReceiver=" + callReceiver);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateCallReceiver(callReceiver);
            }
        });
    }

    private void onPushDescriptor(@NonNull CallDescriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPushDescriptor: descriptor=" + descriptor);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onAddDescriptor(descriptor);
            }
        });
    }

    private void onPopDescriptor(@NonNull CallDescriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPopDescriptor: descriptor=" + descriptor);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onAddDescriptor(descriptor);
            }
        });
    }

    private void onUpdateDescriptor(@NonNull CallDescriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateDescriptor: descriptor=" + descriptor);
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onUpdateDescriptor(descriptor);
            }
        });
    }

    private void onDeleteDescriptors(@NonNull DescriptorId[] descriptorList) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor: descriptorList=" + descriptorList.length);
        }

        mState |= DELETE_DESCRIPTOR_DONE;
        runOnUiThread(() -> {
            if (mObserver != null) {

                Set<DescriptorId> descriptorIdSet = new HashSet<>(Arrays.asList(descriptorList));

                mObserver.onDeleteDescriptors(descriptorIdSet);
            }
        });
    }

    private void onResetConversation(@NonNull Conversation conversation, @NonNull ClearMode clearMode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onResetConversation: conversation=" + conversation + " clearMode=" + clearMode);
        }

        if (mOriginators.containsKey(conversation.getContactId()) && (mOriginatorId == null || mOriginatorId.equals(conversation.getContactId()))) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onResetConversation(conversation, clearMode);
                }
            });
        }
    }

    private void onGetDescriptors(@NonNull List<Descriptor> descriptors) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetDescriptors: descriptors=" + descriptors);
        }

        mState |= GET_DESCRIPTORS_DONE;
        List<CallDescriptor> result = new ArrayList<>();
        for (Descriptor descriptor : descriptors) {
            if (descriptor.getCreatedTimestamp() < mBeforeTimestamp) {
                mBeforeTimestamp = descriptor.getCreatedTimestamp();
            }

            // Filter the calls to keep only the calls for the current space.
            if (mOriginatorTwincodes.contains(descriptor.getTwincodeOutboundId())) {
                result.add((CallDescriptor) descriptor);
            }
        }
        if (descriptors.size() < MAX_OBJECTS) {
            mGetDescriptorsDone = true;
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetDescriptors(result);
            }
        });
    }

    private void onGetCallReceivers(@NonNull List<CallReceiver> callReceivers) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCallReceiver callReceivers.size =" + callReceivers.size());
        }

        mState |= GET_CALL_RECEIVERS_DONE;

        removeOriginatorsByType(Originator.Type.CALL_RECEIVER);

        for (CallReceiver callReceiver : callReceivers) {
            mOriginators.put(callReceiver.getId(), callReceiver);
            mOriginatorTwincodes.add(callReceiver.getTwincodeOutboundId());
        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetCallReceivers(callReceivers);
            }
        });
        onOperation();
    }

    private void onGetGroups(@NonNull List<Group> groups) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetGroups groups.size =" + groups.size());
        }

        mState |= GET_GROUPS_DONE;

        removeOriginatorsByType(Originator.Type.GROUP);

        for (Group group : groups) {
            mOriginators.put(group.getId(), group);
            mOriginatorTwincodes.add(group.getTwincodeOutboundId());
        }

        runOnGetGroups(mObserver, groups);
        onOperation();
    }

    private void onGetCountCallReceivers(int countCallReceivers) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCountCallReceivers");
        }

        mState |= COUNT_CALL_RECEIVERS_DONE;
        mWork &= ~COUNT_CALL_RECEIVERS;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetCountCallReceivers(countCallReceivers);
            }
        });

        onOperation();
    }

    @Override
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
                mSpace = space;
                onOperation();
            });
            return;
        }
        if ((mState & GET_CURRENT_SPACE_DONE) == 0) {
            return;
        }

        //
        // Step 2a: if no originator ID is set, get the list of contacts and call receivers.
        //
        if (mOriginatorId == null) {
            if ((mState & GET_CONTACTS) == 0) {
                mState |= GET_CONTACTS;
                final Filter<RepositoryObject> filter = new Filter<>(mSpace);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.findContacts: filter=" + filter);
                }
                // TwinmeContext.Predicate<Contact> filter = (Contact contact) -> (contact.getSpace() == mSpace);
                mTwinmeContext.findContacts(filter, this::onGetContacts);
                return;
            }
            if ((mState & GET_CONTACTS_DONE) == 0) {
                return;
            }

            if ((mState & GET_CALL_RECEIVERS) == 0) {
                mState |= GET_CALL_RECEIVERS;
                Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof CallReceiver)) {
                            return false;
                        }

                        final CallReceiver callReceiver = (CallReceiver) object;
                        return !callReceiver.isTransfer();
                    }
                };
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.findCallReceivers: filter=" + filter);
                }
                mTwinmeContext.findCallReceivers(filter, this::onGetCallReceivers);
                return;
            }
            if ((mState & GET_CALL_RECEIVERS_DONE) == 0) {
                return;
            }

            if ((mState & GET_GROUPS) == 0) {
                mState |= GET_GROUPS;
                final Filter<RepositoryObject> filter = new Filter<>(mSpace);
                if (DEBUG) {
                    Log.d(LOG_TAG, "TwinmeContext.findCallReceivers: filter=" + filter);
                }
                // TwinmeContext.Predicate<Group> filter = (Group group) -> (group.getSpace() == mSpace);
                mTwinmeContext.findGroups(filter, this::onGetGroups);
                return;
            }
            if ((mState & GET_GROUPS_DONE) == 0) {
                return;
            }
        }

        //
        // Step 2b: if an originator ID is set, get the contact or call receiver.
        //

        if (mOriginatorId != null && mOriginatorType != null) {
            if ((mState & GET_ORIGINATOR) == 0) {
                mState |= GET_ORIGINATOR;

                switch (mOriginatorType) {
                    case CONTACT:
                        mTwinmeContext.getContact(mOriginatorId, this::onGetOriginator);
                        break;
                    case CALL_RECEIVER:
                        mTwinmeContext.getCallReceiver(mOriginatorId, this::onGetOriginator);
                        break;
                    case GROUP:
                        mTwinmeContext.getGroup(mOriginatorId, this::onGetOriginator);
                        break;
                    default:
                        if(DEBUG){
                            Log.d(LOG_TAG, "originator type "+mOriginatorType+" is not supported.");
                        }
                        break;
                }
                return;
            }

            if ((mState & GET_ORIGINATOR_DONE) == 0) {
                return;
            }
        }

        // Step 3: Get the audio/video descriptors.
        //
        if ((mState & GET_DESCRIPTORS) == 0) {
            mState |= GET_DESCRIPTORS;
            long requestId = newOperation(GET_DESCRIPTORS);
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContext.getDescriptors: requestId=" + requestId);
            }

            List<Descriptor> descriptors;
            Descriptor.Type[] types = new Descriptor.Type[] { Descriptor.Type.CALL_DESCRIPTOR };
            if (mConversation != null) {
                descriptors = mTwinmeContext.getConversationService().getConversationTypeDescriptors(mConversation, types, DisplayCallsMode.ALL, mBeforeTimestamp, MAX_OBJECTS);
            } else {
                descriptors = mTwinmeContext.getConversationService().getDescriptors(types, DisplayCallsMode.ALL, mBeforeTimestamp, MAX_OBJECTS);
            }

            if (descriptors != null) {
                onGetDescriptors(descriptors);
            }
            mState |= GET_DESCRIPTORS_DONE;
        }

        //
        // Delete a call receiver
        //

        if (mCallReceiver != null && (mWork & DELETE_CALL_RECEIVER) != 0) {
            if ((mState & DELETE_CALL_RECEIVER) == 0) {
                mState |= DELETE_CALL_RECEIVER;

                long requestId = newOperation(DELETE_CALL_RECEIVER);
                if (DEBUG) {
                    Log.d(LOG_TAG, "mTwinmeContext.deleteCallReceiver: requestId=" + requestId + " callReceiver=" + mCallReceiver);
                }
                mTwinmeContext.deleteCallReceiver(requestId, mCallReceiver);
                return;
            }

            if ((mState & DELETE_CALL_RECEIVER_DONE) == 0) {
                return;
            }
        }

        //
        // Get group members
        //

        if (mGroup != null && (mWork & GET_GROUP_MEMBERS) != 0) {
            if ((mState & GET_GROUP_MEMBERS) == 0) {
                mState |= GET_GROUP_MEMBERS;

                ConversationService.GroupConversation groupConversation = (ConversationService.GroupConversation) mTwinmeContext.getConversationService().getConversation(mGroup);
                if (groupConversation != null) {
                    List<ConversationService.GroupMemberConversation> groupMemberConversations = new ArrayList<>(groupConversation.getGroupMembers(ConversationService.MemberFilter.JOINED_MEMBERS));
                    onGetGroupMembersConversations(groupMemberConversations);
                } else {
                    onGetGroupMembersConversations(new ArrayList<>());
                }

                return;
            }

            if ((mState & GET_GROUP_MEMBERS_DONE) == 0) {
                return;
            }
        }

        //
        // Count all call receivers
        //

        if ((mWork & COUNT_CALL_RECEIVERS) != 0) {
            if ((mState & COUNT_CALL_RECEIVERS) == 0) {
                mState |= COUNT_CALL_RECEIVERS;

                Filter<RepositoryObject> filter = new Filter<RepositoryObject>(null) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof CallReceiver)) {
                            return false;
                        }

                        final CallReceiver callReceiver = (CallReceiver) object;
                        return !callReceiver.isTransfer();
                    }
                };
                mTwinmeContext.findCallReceivers(filter, (List<CallReceiver> callReceivers) -> onGetCountCallReceivers(callReceivers.size()));
                return;
            }

            if ((mState & COUNT_CALL_RECEIVERS_DONE) == 0) {
                return;
            }
        }

        hideProgressIndicator();
    }


    private void removeOriginatorsByType(Originator.Type type) {
        Set<Originator> originatorsToRemove = new HashSet<>();

        for (Originator originator : mOriginators.values()) {
            if (originator.getType() == type) {
                originatorsToRemove.add(originator);
            }
        }

        for (Originator originator : originatorsToRemove) {
            mOriginators.remove(originator.getId());
            mOriginatorTwincodes.remove(originator.getTwincodeOutboundId());
        }
    }

    @Override
    protected void onError(int operationId, ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // We can ignore the error if we try to delete a descriptor that does not exist.
        if (errorCode == ErrorCode.ITEM_NOT_FOUND && operationId == DELETE_DESCRIPTOR) {
            return;
        }

        super.onError(operationId, errorCode, errorParameter);
    }
}
