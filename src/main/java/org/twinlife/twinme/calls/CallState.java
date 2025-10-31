/*
 *  Copyright (c) 2022-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import static org.twinlife.twinme.calls.CallService.MESSAGE_CAMERA_SWITCH;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.conversation.ConversationHandler;
import org.twinlife.twinlife.conversation.DescriptorFactory;
import org.twinlife.twinme.FeatureUtils;
import org.twinlife.twinme.audio.AudioDevice;
import org.twinlife.twinme.calls.keycheck.WordCheckResult;
import org.twinlife.twinme.calls.streaming.StreamPlayer;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Capabilities;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.Originator.Type;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.GeolocationDescriptor;
import org.twinlife.twinlife.PeerCallService;
import org.twinlife.twinlife.PeerConnectionService.ConnectionState;
import org.twinlife.twinlife.TerminateReason;
import org.twinlife.twinme.calls.streaming.Streamer;
import org.twinlife.twinme.calls.streaming.StreamingEvent;
import org.twinlife.twinme.calls.streaming.StreamerImpl;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.Zoomable;
import org.twinlife.twinme.utils.InCallInfo;
import org.twinlife.twinme.utils.MediaMetaData;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.ThreadUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The call state associated with an Audio or Video call:
 * <p>
 * - the audio/video call can have one or several P2P connections (P2P group call)
 * - it can have one or several participants (group call)
 * - it can stream some audio/video to the participants,
 * - it can send and receive descriptors and the sender id must be unique for the whole call duration
 *   (we must not used the twincode, nor the call room identifier)
 * <p>
 * Each P2P connection and participant are maintained separately:
 * <p>
 * - we could have a 1-1 mapping between P2P connection and Participant
 * - we could have a 1-N mapping when the P2P connection is using an SFU as the peer
 *   and we can get several participant for the same P2P connection.
 * <p>
 * When the call is a group call, we have:
 * <p>
 * - a call room identifier,
 * - a member identifier that identifies us within the call room,
 * - the call room configuration (max number of participants, call room options),
 * - a list of member identifiers that participate in the call room (each identifier is a String).
 */
public final class CallState extends DescriptorFactory {
    private static final String LOG_TAG = "CallState";
    private static final boolean DEBUG = false;

    private static final int MAX_MEMBER_UI_SUPPORTED = 8;

    private final List<CallConnection> mPeers = new ArrayList<>();
    private final CallService mCallService;
    @NonNull
    private final PeerCallService mPeerCallService;
    @NonNull
    private final UUID mId;
    @NonNull
    private final UUID mOriginatorId;
    @Nullable
    private final UUID mGroupId;
    @NonNull
    private final List<Descriptor> mDescriptors;
    @Nullable
    private SurfaceViewRenderer mLocalRenderer;
    private long mConnectionStartTime = 0;
    @Nullable
    private volatile Originator mOriginator;
    @Nullable
    private volatile Bitmap mAvatar;
    @Nullable
    private volatile Bitmap mIdentityAvatar;
    @Nullable
    private volatile Bitmap mGroupAvatar;
    @Nullable
    private volatile Capabilities mIdentityCapabilities;
    @Nullable
    private UUID mPeerTwincodeOutboundId;
    @Nullable
    private UUID mTwincodeOutboundId;
    private boolean mPeerConnected;
    @Nullable
    private DescriptorId mCallDescriptorId;
    @Nullable
    private TerminateReason mTerminateReason;
    @Nullable
    private UUID mCallRoomId;
    @Nullable
    private String mCallRoomMemberId;

    @Nullable
    private TransferDirection transferDirection = null;

    /**
     * When not null, indicates that this connection is going to be transferred.
     */
    @Nullable
    private CallConnection mTransferFromConnection;

    /**
     * When not null, indicate that mTransferFromConnection's participant is transferring to this member.
     * Set upon receiving the ParticipantTransferIQ from the transferred device.
     */
    @Nullable
    private String mTransferToMemberId;

    /**
     * During a transfer, contains the new incoming connections initiated after receiving PrepareTransferIQ.
     */
    private final Set<UUID> mPendingCallRoomMembers = new HashSet<>();

    private final Set<UUID> mPendingPrepareTransfers = new HashSet<>();
    private UUID mPendingChangeStateConnectionId = null;

    /**
     * Used for click-to-call group calls: if several participants start the call at the same time,
     * the first one will be processed while the others will be added to this Set in handleIncomingCallDuringExistingCall().
     * <p>
     * If the call is accepted we resume them in onChangeConnectionState(). If the call is rejected they will be terminated.
     */
    private final Set<CallConnection> mIncomingGroupCallConnections = new HashSet<>();

    private int mMaxMemberCount = 0;
    private int mState = 0;
    @NonNull
    private final Handler mHandler;
    @Nullable
    private StreamerImpl mStreamer;
    private int mLastStreamIdent;
    private final AtomicLong mRequestCounter;
    private final AtomicLong mSequenceCounter;
    private final UUID mSenderId;
    private boolean mAudioSourceOn = true;
    private boolean mVideoSourceOn = false;
    @Nullable
    private GeolocationDescriptor mCurrentGeolocation;

    @NonNull
    private CameraType mCameraType = CameraType.FRONT;

    private boolean mOnHold = false;

    private boolean mTelecomFailed = false;
    private boolean mOutgoingTelecomCallRegistered = false;

    private final boolean mIncomingCall;

    @NonNull
    public UUID getId() {
        return mId;
    }

    /**
     * Get the originator id associated with this audio/video call.
     *
     * @return the originator id.
     */
    @NonNull
    public UUID getOriginatorId() {

        return mOriginatorId;
    }

    /**
     * Get the originator object (when it was resolved).
     *
     * @return the originator.
     */
    @Nullable
    public Originator getOriginator() {

        return mOriginator;
    }

    /**
     * Get the originator avatar (when it was resolved).
     *
     * @return the originator avatar.
     */
    @Nullable
    public Bitmap getAvatar() {

        return mAvatar;
    }

    /**
     * Get the originator's group avatar (if the originator is a group member and when it was resolved).
     *
     * @return the originator's group avatar. null if the originator is not a group member
     */
    @Nullable
    public Bitmap getGroupAvatar() {

        return mGroupAvatar;
    }


    /**
     * Get the identity avatar (when it was resolved).
     *
     * @return the identity avatar.
     */
    @Nullable
    public Bitmap getIdentityAvatar() {

        return mIdentityAvatar;
    }

    /**
     * Get the zoomable config which indicates whether the peer can take control
     * of our video and zoom.
     * @return the zoomable state.
     */
    @NonNull
    public Zoomable isZoomableByPeer() {

        final Capabilities capabilities = mIdentityCapabilities;
        return capabilities == null ? Zoomable.ASK : capabilities.getZoomable();
    }

    /**
     * Get the descriptor id associated with this call.
     *
     * @return the call conversation descriptor id.
     */
    public synchronized DescriptorId getCallDescriptorId() {

        return mCallDescriptorId;
    }

    /**
     * Returns true if the peer is connected.
     *
     * @return true if the peer is connected.
     */
    public synchronized boolean isConnected() {

        return mPeerConnected;
    }

    /**
     * Returns true if the call handles video.
     *
     * @return true if the call handles video.
     */
    public boolean isVideo() {

        return getStatus().isVideo();
    }

    /**
     * Returns true if this call is a group call.  The call is changed to a group call when a first participant is added.
     *
     * @return true if this is a group call.
     */
    public synchronized boolean isGroupCall() {

        return mCallRoomId != null || mPeers.size() > 1;
    }

    synchronized void putOnHold() {
        if (mOnHold) {
            return;
        }
        for (CallConnection connection : getConnections()) {
            connection.putOnHold();
            connection.sendHoldCallIQ();
        }

        if (mStreamer != null && mStreamer.getPlayer() != null && !mStreamer.getPlayer().isPause()) {
            mStreamer.pauseStreaming();
        }

        mOnHold = true;
    }

    synchronized void resume() {
        if (!mOnHold) {
            return;
        }

        for(CallConnection connection: getConnections()) {
            if (connection.getStatus() != CallStatus.PEER_ON_HOLD) {
                connection.resume();
                connection.mStatus = mVideoSourceOn ? CallStatus.IN_VIDEO_CALL : CallStatus.IN_CALL;
                connection.sendResumeCallIQ();
            }
        }

        if (mStreamer != null && mStreamer.getPlayer() != null && mStreamer.getPlayer().getStreamer() != null) {
            StreamPlayer player = mStreamer.getPlayer();
            player.getStreamer().resumeStreaming();
        }

        mOnHold = false;
    }

    /**
     * Get the current call status, based on its connections' status:
     * <ol>
     *     <li>If at least one of the connections is ACTIVE, return ACTIVE</li>
     *     <li>Otherwise if at least one of the connections is ACCEPTED, return ACCEPTED</li>
     *     <li>Otherwise return the status of the first connection</li>
     * </ol>
     *
     * @return the current call status.
     */
    @NonNull
    public synchronized CallStatus getStatus() {

        if (mPeers.isEmpty()) {

            return CallStatus.TERMINATED;
        }

        if (mOnHold) {
            return CallStatus.ON_HOLD;
        }

        CallStatus relevantStatus = mPeers.get(0).getStatus();

        boolean allPeersOnHold = true;

        for (CallConnection connection : mPeers) {
            if (connection.getStatus() == CallStatus.PEER_ON_HOLD) {
                continue;
            }

            allPeersOnHold = false;

            if (CallStatus.isActive(connection.getStatus())) {
                return connection.getStatus();
            }

            if (CallStatus.isAccepted(connection.getStatus())) {
                relevantStatus = connection.getStatus();
            }
        }

        return allPeersOnHold ? CallStatus.PEER_ON_HOLD : relevantStatus;
    }

    /**
     * Check if the call is terminated.
     *
     * @return the call is terminated when it has no peers.
     */
    public synchronized boolean isTerminated() {

        return mPeers.isEmpty();
    }

    @NonNull
    public synchronized InCallInfo getInCallInfo() {
        Bitmap avatar;
        UUID groupId;

        final Originator originator = mOriginator; // a volatile field must be saved locally before being accessed.
        if (originator instanceof GroupMember) {
            groupId = ((GroupMember) originator).getGroup().getId();
            avatar = getGroupAvatar();
        } else {
            groupId = null;
            avatar = getAvatar();
        }

        boolean isVideo = getMainParticipant() != null && !getMainParticipant().isCameraMute();
        return new InCallInfo(originator != null ? originator.getId() : null, groupId, avatar, null, getStatus(), isVideo);
    }

    /**
     * Get the main participant for the call.
     *
     * @return the main participant.
     */
    @Nullable
    public synchronized CallParticipant getMainParticipant() {

        if (mPeers.isEmpty()) {
            return null;
        } else {
            return mPeers.get(0).getMainParticipant();
        }
    }

    /**
     * Get the local video renderer if the camera is opened.
     *
     * @return the local video renderer or null.
     */
    @Nullable
    public synchronized SurfaceViewRenderer getLocalRenderer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getLocalRenderer");
        }

        return mLocalRenderer;
    }

    /**
     * Get the main remote renderer for the video call.
     *
     * @return the main remote renderer or null if there is no video.
     */
    @Nullable
    public synchronized SurfaceViewRenderer getRemoteRenderer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getRemoteRenderer");
        }

        if (mPeers.isEmpty()) {

            return null;
        }

        final CallConnection callConnection = mPeers.get(0);
        if (callConnection == null) {

            return null;
        }

        return callConnection.getRemoteRenderer();
    }

    /**
     * Get the list of call participants.
     *
     * @return the current frozen list of participants.
     */
    @NonNull
    public synchronized List<CallParticipant> getParticipants() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getParticipants");
        }

        // Get a copy of the participants because some operations could remove them while we iterate.
        final List<CallParticipant> result = new ArrayList<>();
        for (CallConnection connection : mPeers) {
            if (!mPendingCallRoomMembers.contains(connection.getPeerConnectionId())) {
                connection.getParticipants(result);
            }
        }

        return result;
    }

    @Nullable
    public synchronized CallConnection getInitialConnection() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentConnection");
        }

        if (mPeers.isEmpty()) {

            return null;
        }

        return mPeers.get(0);
    }

    /**
     * Get the call room identifier.
     *
     * @return the call room identifier or null if this is not a group call.
     */
    @Nullable
    public synchronized UUID getCallRoomId() {

        return mCallRoomId;
    }

    @Nullable
    public synchronized String getCallRoomMemberId() {
        return mCallRoomMemberId;
    }

    public void setMaxMemberCount(int maxMemberCount) {
        mMaxMemberCount = Math.min(MAX_MEMBER_UI_SUPPORTED, maxMemberCount);
    }

    /**
     * Get the maximum number of participants
     *
     * @return the maximum number of participants.
     */
    public int getMaxMemberCount() {

        return mMaxMemberCount;
    }

    /**
     * Get our current geolocation that was sent to the peers.
     * It is created when `sendGeolocation` is called for the first time.
     * The same instance is used for multiple calls to `sendGeolocation`.
     * It is cleared when `deleteGeolocation` is called.
     *
     * @return our current geolocation or null.
     */
    @Nullable
    public GeolocationDescriptor getCurrentGeolocation() {

        return mCurrentGeolocation;
    }

    /**
     * Send the descriptor to the connected participants if they support receiving a descriptor.
     * It must be called from the main UI thread only.
     *
     * @param descriptor the descriptor to send.
     * @return true if the descriptor was serialized and sent.
     */
    public boolean sendDescriptor(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendDescriptor descriptor=" + descriptor);
        }

        ThreadUtils.checkIsOnMainThread();

        mDescriptors.add(descriptor);

        boolean result = false;
        final List<CallConnection> connectionList = getConnections();
        final boolean isGeoloc = descriptor.getType() == Descriptor.Type.GEOLOCATION_DESCRIPTOR;
        for (CallConnection connection : connectionList) {
            if (isGeoloc) {
                if (Boolean.TRUE.equals(connection.isGeolocSupported())) {
                    result |= connection.sendDescriptor(descriptor);
                }
            } else {
                if (Boolean.TRUE.equals(connection.isMessageSupported())) {
                    result |= connection.sendDescriptor(descriptor);
                }
            }
        }
        return result;
    }

    /**
     * Send the geolocation to the connected participants if they support receiving a geolocation.
     * It must be called from the main UI thread only.  The first call creates the Geolocation description
     * and other calls will update it until deleteGeolocation() is called.
     *
     * @param {longitude, latitude, altitude, mapLongitudeDelta, mapLatitudeDelta}  the geolocation to send.
     * @return true if the descriptor was serialized and sent.
     */
    public boolean sendGeolocation(double longitude, double latitude, double altitude,
                                   double mapLongitudeDelta, double mapLatitudeDelta) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendGeolocation longitude=" + longitude + " latitude=" + latitude + " altitude=" + altitude);
        }

        ThreadUtils.checkIsOnMainThread();

        if (mCurrentGeolocation == null) {
            mCurrentGeolocation = createGeolocation(longitude, latitude, altitude, mapLongitudeDelta, mapLatitudeDelta);
            return sendDescriptor(mCurrentGeolocation);
        }

        boolean result = false;
        final List<CallConnection> connectionList = getConnections();
        for (CallConnection connection : connectionList) {
            if (Boolean.TRUE.equals(connection.isGeolocSupported())) {
                result |= connection.updateGeolocation(mCurrentGeolocation, longitude, latitude, altitude, mapLongitudeDelta, mapLatitudeDelta);
            }
        }
        return result;
    }

    public boolean deleteGeolocation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteGeolocation");
        }

        ThreadUtils.checkIsOnMainThread();

        if (mCurrentGeolocation == null) {
            return false;
        }

        boolean result = false;
        final List<CallConnection> connectionList = getConnections();
        for (CallConnection connection : connectionList) {
            if (Boolean.TRUE.equals(connection.isGeolocSupported())) {
                result |= connection.deleteDescriptor(mCurrentGeolocation);
            }
        }
        mCurrentGeolocation = null;
        return result;
    }

    public void markDescriptorRead(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "markDescriptorRead descriptor=" + descriptor);
        }

        ConversationHandler.markDescriptorRead(descriptor);
    }

    /**
     * Get the descriptors that have been received and sent.
     * It must be called from the main UI thread only.
     *
     * @return the list of descriptors in the order in which they are sent & received.
     */
    public List<Descriptor> getDescriptors() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getDescriptors");
        }

        ThreadUtils.checkIsOnMainThread();

        return mDescriptors;
    }

    /**
     * Check if descriptor is local or send by peer
     * It must be called from the main UI thread only.
     *
     * @param descriptor the descriptor to add in call conversation
     * @return true if the descriptor was send from peer.
     */
    public boolean isPeerDescriptor(Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isPeerDescriptor: " + descriptor);
        }

        return !descriptor.getTwincodeOutboundId().equals(mSenderId);
    }

    @Nullable
    public TransferDirection getTransferDirection() {
        return transferDirection;
    }

    void setTransferDirection(@Nullable TransferDirection transferDirection) {
        this.transferDirection = transferDirection;
    }

    boolean isAudioSourceOn() {
        return mAudioSourceOn;
    }

    void setAudioSourceOn(boolean enabled) {
        mAudioSourceOn = enabled;
    }

    boolean isVideoSourceOn() {
        return mVideoSourceOn;
    }

    public boolean isFrontCamera() {
        return mCameraType == CameraType.FRONT;
    }

    public void setCameraType(@NonNull CameraType cameraType) {
        mCameraType = cameraType;
        mCallService.sendMessage(MESSAGE_CAMERA_SWITCH);
    }

    void setVideoSourceOn(boolean enabled) {
        mVideoSourceOn = enabled;
    }

    void setAudioVideoState(@NonNull CallStatus status) {
        switch (status) {
            case OUTGOING_VIDEO_BELL:
                mAudioSourceOn = false;
                mVideoSourceOn = true;
                break;

            case OUTGOING_CALL:
            case INCOMING_CALL:
            case ACCEPTED_INCOMING_CALL:
                mAudioSourceOn = true;
                mVideoSourceOn = false;
                break;

            case OUTGOING_VIDEO_CALL:
            case INCOMING_VIDEO_CALL:
            case ACCEPTED_INCOMING_VIDEO_CALL:
                mAudioSourceOn = true;
                mVideoSourceOn = true;
                break;

            case INCOMING_VIDEO_BELL:
            default:
                mAudioSourceOn = false;
                mVideoSourceOn = false;
                break;
        }
    }

    /*
     * Internal methods.
     */

    /**
     * A new participant is added to the call group.
     *
     * @param participant the participant.
     */
    void onAddParticipant(@NonNull CallParticipant participant) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAddParticipant participant=" + participant);
        }

        final CallParticipantObserver observer = mCallService.getParticipantObserver();
        if (observer != null) {
            mHandler.post(() -> observer.onAddParticipant(participant));
        }
    }

    /**
     * One or several participants are removed from the call.
     *
     * @param participants the list of participants being removed.
     */
    void onRemoveParticipants(@NonNull List<CallParticipant> participants) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRemoveParticipants participants=" + participants);
        }

        final CallParticipantObserver observer = mCallService.getParticipantObserver();
        if (observer != null) {
            mHandler.post(() -> observer.onRemoveParticipants(participants));
        }
    }

    /**
     * An event occurred for the participant and its state was changed.
     *
     * @param participant the participant.
     * @param event the event that occurred.
     */
    void onEventParticipant(@NonNull CallParticipant participant, @NonNull CallParticipantEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onEventParticipant participant=" + participant + " event=" + event);
        }

        if (event == CallParticipantEvent.EVENT_CONNECTED && CallService.isLocationStartShared() && Boolean.TRUE.equals(participant.isGeolocSupported())) {
            mHandler.post(() -> mCallService.sendGeolocation(participant.getCallConnection()));
        }

        final CallParticipantObserver observer = mCallService.getParticipantObserver();
        if (observer != null) {
            mHandler.post(() -> observer.onEventParticipant(participant, event));
        }
    }

    /**
     * A participant is transferring the call, we've received the transfer target memberId
     * so we can safely add all pending members to the call room.
     */
    synchronized void onEventParticipantTransfer(@NonNull String memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onEventParticipantTransfer memberId="+memberId);
        }

        mTransferToMemberId = memberId;
        mPendingCallRoomMembers.clear();

        for (CallConnection connection: mPeers) {
            if (connection.getCallMemberId() != null && connection.getCallMemberId().equals(memberId)) {
                performTransfer(connection.getMainParticipant());
                break;
            }
        }
    }

    /**
     * The participant has sent us a descriptor.
     *
     * @param participant the participant.
     * @param descriptor the descriptor that was sent.
     */
    void onPopDescriptor(@Nullable CallParticipant participant, @NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPopDescriptor participant=" + participant + " descriptor=" + descriptor);
        }

        // Add the descriptor in the list from the main UI thread to make sure we don't change
        // the list in case the UI is scanning it for the display.
        mHandler.post(() -> {
            mDescriptors.add(descriptor);
            final CallParticipantObserver observer = mCallService.getParticipantObserver();
            if (observer != null) {
                observer.onPopDescriptor(participant, descriptor);
            }
        });
    }

    /**
     * The participant has updated its geolocation descriptor.
     *
     * @param participant the participant.
     * @param descriptor the geolocation descriptor updated with new position.
     */
    void onUpdateGeolocation(@Nullable CallParticipant participant, @NonNull GeolocationDescriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateGeolocation participant=" + participant + " descriptor=" + descriptor);
        }

        mHandler.post(() -> {
            final CallParticipantObserver observer = mCallService.getParticipantObserver();
            if (observer != null) {
                observer.onUpdateGeolocation(participant, descriptor);
            }
        });
    }

    /**
     * The participant has deleted its descriptor.
     *
     * @param participant the participant.
     * @param descriptorId the descriptor that was deleted.
     */
    void onDeleteDescriptor(@Nullable CallParticipant participant, @NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor participant=" + participant + " descriptorId=" + descriptorId);
        }

        mHandler.post(() -> {
            final CallParticipantObserver observer = mCallService.getParticipantObserver();
            if (observer != null) {
                observer.onDeleteDescriptor(participant, descriptorId);
            }
        });
    }

    public boolean performTransfer(@NonNull CallParticipant transferTarget) {
        if (DEBUG) {
            Log.d(LOG_TAG, "performTransfer transferTarget=" + transferTarget);
        }

        if (mTransferFromConnection == null) {
            return false;
        }

        String transferToMemberId = mTransferFromConnection.getTransferToMemberId();

        if (transferToMemberId != null && transferToMemberId.equals(transferTarget.getCallConnection().getCallMemberId())) {
            transferTarget.transfer(mTransferFromConnection.getMainParticipant());
            mTransferFromConnection.setTransferToMemberId(null);
            mTransferFromConnection = null;
            mTransferToMemberId = null;

            final CallParticipantObserver observer = mCallService.getParticipantObserver();
            if (observer != null) {
                mHandler.post(() -> observer.onEventParticipant(transferTarget, CallParticipantEvent.EVENT_IDENTITY));
            }

            return true;
        }

        return false;
    }

    /**
     * An event occurred on the streamer.
     *
     * @param event the event that occurred.
     */
    public void onEventStreaming(@Nullable CallParticipant streamerParticipant, @NonNull StreamingEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onEventStreaming event=" + event);
        }

        final CallParticipantObserver observer = mCallService.getParticipantObserver();
        if (observer != null) {
            mHandler.post(() -> observer.onEventStreaming(streamerParticipant, event));
        }
    }

    public void onTransferDone() {
        mPeerCallService.transferDone();
    }

    public void onPrepareTransfer(@NonNull CallConnection callConnection) {
        mTransferFromConnection = callConnection;
    }

    public void onOnPrepareTransfer(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOnPrepareTransfer: peerConnectionId=" + peerConnectionId);
        }

        if (getConnectionById(peerConnectionId) == null) {
            return;
        }

        boolean allDone;
        synchronized (mPendingPrepareTransfers) {
            mPendingPrepareTransfers.remove(peerConnectionId);
            allDone = mPendingPrepareTransfers.isEmpty();
        }

        // We got ACKs for all PrepareTransferIQs =>
        // Every participant is prepared to receive an incoming connection from the transfer target =>
        // invite the target to the group if it's ready.
        if (allDone && mPendingChangeStateConnectionId != null) {
            // onChangeConnectionStateWithConnection was already called once with state == Connected,
            // but we were still waiting on ACKs from existing call participants.
            // Now they're all aware a transfer is in progress, and they will handle the transfer target participant properly =>
            // invite the transfer target to the call room.

            mCallService.onChangeConnectionState(mPendingChangeStateConnectionId, ConnectionState.CONNECTED);
            mPendingChangeStateConnectionId = null;
        }
    }

    public void onPeerHoldCall(@NonNull UUID peerConnectionId) {
        mCallService.onPeerHoldCall(peerConnectionId);
    }

    public void onPeerResumeCall(@NonNull UUID peerConnectionId) {
        mCallService.onPeerResumeCall(peerConnectionId);
    }

    public void onKeyCheckInitiate(@NonNull UUID peerConnectionId, @NonNull Locale locale) {
        mCallService.onPeerKeyCheckInitiate(peerConnectionId, locale);
    }

    public void onOnKeyCheckInitiate(@NonNull UUID peerConnectionId, @NonNull ErrorCode errorCode) {
        mCallService.onOnKeyCheckInitiate(peerConnectionId, errorCode);
    }

    public void onWordCheck(@NonNull UUID peerConnectionId, @NonNull WordCheckResult wordCheckResult) {
        mCallService.onPeerWordCheckResult(peerConnectionId, wordCheckResult);
    }

    public void onTerminateKeyCheck(@NonNull UUID peerConnectionId, boolean wordCheckResult) {
        mCallService.onTerminateKeyCheck(peerConnectionId, wordCheckResult);
    }

    public void onTwincodeURI(@NonNull UUID peerConnectionId, @NonNull String uri) {
        mCallService.onTwincodeURI(peerConnectionId, uri);
    }

    int allocateParticipantId() {

        return mCallService.allocateParticipantId();
    }

    /**
     * Allocate a unique request id (unique in the call).
     *
     * @return the new request id.
     */
    public long allocateRequestId() {

        return mRequestCounter.incrementAndGet();
    }

    @NonNull
    public DescriptorId newDescriptorId() {

        return new DescriptorId(0, mSenderId, mSequenceCounter.incrementAndGet());
    }

    /**
     * Check if we already have a peer connection to the given call room member.
     *
     * @param callRoomMemberId the call room member id.
     * @return true if we already have a peer connection.
     */
    synchronized boolean hasConnection(@NonNull String callRoomMemberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "hasConnection " + callRoomMemberId);
        }

        for (CallConnection connection : mPeers) {
            if (callRoomMemberId.equals(connection.getCallMemberId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the list of connections.
     *
     * @return the current frozen list of connections.
     */
    @NonNull
    public synchronized List<CallConnection> getConnections() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getConnections");
        }

        // Get a copy of the peer connections because some operations could remove them while we iterate.
        return new ArrayList<>(mPeers);
    }

    public synchronized void clearConnections() {
        mPeers.clear();
    }

    @Nullable
    synchronized CallConnection getConnectionById(@NonNull UUID peerConnectionId) {
        for (CallConnection connection : mPeers) {
            if (peerConnectionId.equals(connection.getPeerConnectionId())) {
                return connection;
            }
        }
        return null;
    }

    @NonNull
    synchronized List<Pair<UUID, String>> getConnectionIds() {
        final List<Pair<UUID, String>> res = new ArrayList<>();
        for (CallConnection connection : mPeers) {
            final UUID sessionId = connection.getPeerConnectionId();
            if (sessionId != null) {
                final String callMemberId = connection.getCallMemberId();
                if (callMemberId != null) {
                    res.add(new Pair<>(sessionId, callMemberId));
                } else {
                    final UUID peerTwincodeId = connection.getPeerTwincodeOutboundId();
                    if (peerTwincodeId == null) {
                        res.add(new Pair<>(sessionId, null));
                    } else {
                        res.add(new Pair<>(sessionId, peerTwincodeId.toString()));
                    }
                }
            }
        }

        return res;
    }

    @Nullable
    UUID getTwincodeOutboundId() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getTwincodeOutboundId");
        }

        final Originator originator = mOriginator;

        if (originator == null) {
            return null;
        }

        if (originator.getType() == Originator.Type.GROUP_MEMBER) {
            return ((GroupMember)originator).getGroup().getTwincodeOutboundId();
        }else{
            return originator.getTwincodeOutboundId();
        }
    }

    synchronized boolean onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact contact=" + contact);
        }

        mOriginator = contact;
        mIdentityCapabilities = contact.getIdentityCapabilities();

        CallStatus status = getStatus();
        if (CallStatus.isOutgoing(status) && contact.getPeerTwincodeOutboundId() != null && mPeerTwincodeOutboundId == null) {

            // Now we have the contact, trigger the outgoing call in the service.
            mPeerTwincodeOutboundId = contact.getPeerTwincodeOutboundId();
            return true;

        } else {
            return false;
        }
    }

    /**
     * Get the current streamer for this call.
     *
     * @return the streamer or null.
     */
    @Nullable
    public Streamer getCurrentStreamer() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentStreamer");
        }

        return mStreamer;
    }

    public Context getContext() {

        return mCallService;
    }

    /**
     * Start streaming a content defined by the uri and provided by the content resolver.
     *
     * @param contentResolver the content resolver.
     * @param uri the uri to stream.
     * @param mediaMetaData the optional information describing the media.
     * @param executor the executor to handle the streaming.
     */
    void startStreaming(@NonNull ContentResolver contentResolver, @NonNull Uri uri,
                        @Nullable MediaMetaData mediaMetaData, @NonNull ScheduledExecutorService executor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startStreaming uri=" + uri + " mediaMetaData=" + mediaMetaData);
        }

        final StreamerImpl oldStreamer;
        final StreamerImpl newStreamer;
        synchronized (this) {
            oldStreamer = mStreamer;
            mLastStreamIdent++;
            mStreamer = newStreamer = new StreamerImpl(this, mLastStreamIdent, mediaMetaData, executor);
        }
        if (oldStreamer != null) {
            oldStreamer.stopStreaming(true);
        }

        newStreamer.startStreaming(contentResolver, uri);
    }

    /**
     * Stop streaming content, notify the peers to stop their player and release all resources.
     *
     * @param notify when true notify peers about the stop streaming action.
     */
    public void stopStreaming(boolean notify) {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopStreaming");
        }

        if (mStreamer != null) {
            mStreamer.stopStreaming(notify);
            mStreamer = null;
        }
    }

    public void setTelecomFailed(boolean telecomFailed) {
        mTelecomFailed = telecomFailed;
    }

    public void setOutgoingTelecomCallRegistered(boolean outgoingTelecomCallRegistered) {
        mOutgoingTelecomCallRegistered = outgoingTelecomCallRegistered;
    }

    public boolean isOutgoingTelecomCallRegistered() {
        return mOutgoingTelecomCallRegistered;
    }

    CallState(@NonNull CallService callService, @NonNull PeerCallService peerCallService,
              @NonNull UUID originatorId, @Nullable UUID groupId, boolean incoming) {

        mHandler = new Handler(Looper.getMainLooper());
        mCallService = callService;
        mPeerCallService = peerCallService;
        mOriginatorId = originatorId;
        mGroupId = groupId;
        mLastStreamIdent = 0;
        mRequestCounter = new AtomicLong();
        mSequenceCounter = new AtomicLong();
        mSenderId = UUID.randomUUID();
        mDescriptors = new ArrayList<>();
        mId = UUID.randomUUID();
        mIncomingCall = incoming;
    }

    @NonNull
    public Handler getHandler() {

        return mHandler;
    }

    synchronized void setOriginator(@NonNull Originator originator, @Nullable Bitmap avatar, @Nullable Bitmap identityAvatar, @Nullable Bitmap groupAvatar) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setOriginator: originator=" + originator);
        }

        mOriginator = originator;
        mAvatar = avatar;
        mIdentityAvatar = identityAvatar;
        mGroupAvatar = groupAvatar;
        mIdentityCapabilities = originator.getIdentityCapabilities();

        mPeerTwincodeOutboundId = originator.getPeerTwincodeOutboundId();

        if (originator.getType() == Type.GROUP_MEMBER) {
            mTwincodeOutboundId = ((GroupMember)originator).getGroup().getTwincodeOutboundId();
        } else {
            mTwincodeOutboundId = originator.getTwincodeOutboundId();
        }
    }

    synchronized void startCall(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startCall: descriptorId=" + descriptorId);
        }

        mCallDescriptorId = descriptorId;
    }

    /**
     * Set the local video renderer when the camera is opened.
     */
    synchronized void setLocalRenderer(@NonNull SurfaceViewRenderer localRenderer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setLocalRenderer: localRenderer=" + localRenderer);
        }

        mLocalRenderer = localRenderer;
    }

    /**
     * Add a new peer connection to a contact.
     *
     * @param callConnection the peer connection to add.
     */
    synchronized void addPeerConnection(@NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "addPeerConnection: callParticipant=" + callConnection);
        }

        mPeers.add(callConnection);

        if (mTransferFromConnection != null) {
            // We've received a PrepareTransferIQ from the transferred participant
            // => this new incoming connection is likely from the transfer target,
            // but it could also be a new participant joining the call at the same time.
            // We don't want to display the transfer target as a new participant, it should seamlessly replace the transferred participant.
            // So we have to wait until we receive the ParticipantTransferIQ (which identifies the transfer target) from the transferred participant.

            if (mTransferToMemberId == null) {
                // We haven't received the ParticipantTransferIQ yet.
                // Connections in pendingCallRoomMembers are ignored by the ViewController (see CallState.getParticipants).
                mPendingCallRoomMembers.add(callConnection.getPeerConnectionId());
            } else if (mTransferToMemberId.equals(callConnection.getCallMemberId())) {
                // We've received a ParticipantTransferIQ from the transferred participant,
                // and this is the transfer target's connection => perform the transfer
                performTransfer(callConnection.getMainParticipant());
            }
        }
    }

    public void showIncomingCallUi(@NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "showIncomingCallUi");
        }

        Originator originator = getOriginator();
        if (originator == null || !originator.getCapabilities().hasAutoAnswerCall()) {
            mCallService.startIncomingCallRingtone(callConnection);
        }
    }

    public void onCallAudioStateChanged(@NonNull AudioDevice activeDevice, @NonNull Set<AudioDevice> availableDevices) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCallAudioStateChanged: activeDevice=" + activeDevice + " availableDevices=" + availableDevices);
        }

        mCallService.onAudioDeviceChanged(activeDevice, availableDevices);
    }

    enum UpdateState {
        // Ignore this update connection
        IGNORE,

        // the audio/video is now connected for the first time.
        FIRST_CONNECTION,

        // connected and not yet in a call room
        FIRST_GROUP,

        // new connection is active and we are in a call room
        NEW_CONNECTION
    }

    /**
     * Update the call connection state after the P2P connection has reached the given connection state.
     *
     * @param callConnection the P2P connection.
     * @param state the connection state of that P2P connection.
     * @return the update state of this connection.
     */
    synchronized UpdateState updateConnectionState(@NonNull CallConnection callConnection, @NonNull ConnectionState state) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setConnectionState: callParticipant=" + callConnection + " state=" + state);
        }

        // Keep the call status before updating the state because it may change.
        final CallStatus status = callConnection.getStatus();
        if (!callConnection.updateConnectionState(state)) {

            return UpdateState.IGNORE;
        }

        if (mConnectionStartTime != 0) {

            if (mPeers.size() == 1) {

                return UpdateState.IGNORE;

            } else if (mCallRoomId == null) {

                return UpdateState.FIRST_GROUP;

            } else {

                return UpdateState.NEW_CONNECTION;
            }
        }

        if (!CallStatus.isAccepted(status) && !CallStatus.isOutgoing(status)) {

            return UpdateState.IGNORE;
        }

        // Call is accepted and we are connected for the first time.
        mConnectionStartTime = callConnection.getConnectionStartTime();
        mPeerConnected = true;
        return UpdateState.FIRST_CONNECTION;
    }

    void putState(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "putState: intent=" + intent);
        }

        final CallConnection callConnection;
        synchronized (this) {
            intent.putExtra(CallService.CALL_ID, mId);
            intent.putExtra(CallService.CALL_CONTACT_ID, mOriginatorId);
            if (mGroupId != null) {
                intent.putExtra(CallService.CALL_GROUP_ID, mGroupId);
            }

            intent.putExtra(CallService.CALL_SERVICE_STATE, getStatus());
            intent.putExtra(CallService.CALL_SERVICE_CONNECTION_START_TIME, mConnectionStartTime);
            if (mTerminateReason != null) {
                intent.putExtra(CallService.CALL_SERVICE_TERMINATE_REASON, mTerminateReason);
            }
            callConnection = getInitialConnection();
        }

        // We must release the lock on CallState before calling putState() otherwise a deadlock can occur.
        if (callConnection != null) {
            callConnection.putState(intent);
        }
    }

    /**
     * Check if the operation was executed for this call and prepare to execute it.
     *
     * This is used only for the creation and management of the call room which is shared by all P2P connections.
     *
     * @param operation the operation to execute.
     * @return true if the operation must be executed NOW.
     */
    synchronized boolean checkOperation(int operation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "checkOperation: operation=" + operation);
        }

        if ((mState & operation) == 0) {
            mState |= operation;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if the operation was executed and finished.
     *
     * @param operation the operation done flag to check.
     * @return true if the operation was done.
     */
    synchronized boolean isDoneOperation(int operation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isDoneOperation: operation=" + operation);
        }

        return (mState & operation) != 0;
    }

    /**
     * Create the call room by using the PeerCallService and sending to the server
     * the list of members with their current P2P session ids.
     *
     * @param requestId the request id.
     */
    void createCallRoom(long requestId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createCallRoom: requestId=" + requestId);
        }

        final Map<String, UUID> members = new HashMap<>();
        synchronized (this) {
            for (CallConnection peer : mPeers) {
                final UUID peerTwincodeOutboundId = peer.getPeerTwincodeOutboundId();
                final UUID sessionId = peer.getPeerConnectionId();
                final Boolean groupSupported = peer.isGroupSupported();

                if (peerTwincodeOutboundId != null && (groupSupported == null || groupSupported)) {
                    members.put(peerTwincodeOutboundId.toString(), sessionId);

                    // This member is invited as part of the CallRoom creation (no need for a call to inviteCallRoom).
                    peer.setInvited(true);
                }
            }
        }

        mPeerCallService.createCallRoom(requestId, mTwincodeOutboundId, members);
    }

    /**
     * Invite the member for which we have a new call connection to participate in the call group.
     * This sends an invitation to join and when the peer accepts the invitation it will get other
     * member information to setup P2P sessions with them.
     *
     * @param requestId the request id.
     */
    void inviteCallRoom(long requestId, @NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "inviteCallRoom: requestId=" + requestId + " callConnection=" + callConnection);
        }

        final UUID peerTwincodeOutboundId = callConnection.getPeerTwincodeOutboundId();
        if (peerTwincodeOutboundId == null || mCallRoomId == null || !Boolean.TRUE.equals(callConnection.isGroupSupported())) {
            return;
        }

        mPeerCallService.inviteCallRoom(requestId, mCallRoomId, peerTwincodeOutboundId, callConnection.getPeerConnectionId());
    }

    /**
     * Leave the call room.
     *
     * @param requestId the request id.
     */
    void leaveCallRoom(long requestId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "leaveCallRoom: requestId=" + requestId);
        }

        if (mCallRoomId != null && mCallRoomMemberId != null) {
            mPeerCallService.leaveCallRoom(requestId, mCallRoomId, mCallRoomMemberId);
        }
    }

    /**
     * Join the call room and give our P2P sessions that we know.
     *
     * @param callRoomId the call room to join once the call is accepted.
     * @param maxMemberCount the number of participants supported by the callroom.
     */
    void joinCallRoom(long requestId, @NonNull UUID callRoomId, int maxMemberCount) {
        if (DEBUG) {
            Log.d(LOG_TAG, "joinCallRoom: callRoomId=" + callRoomId);
        }

        final Originator originator;
        synchronized (this) {
            mState |= ConnectionOperation.CREATE_CALL_ROOM | ConnectionOperation.CREATE_CALL_ROOM_DONE;
            mCallRoomId = callRoomId;
            mMaxMemberCount = Math.min(MAX_MEMBER_UI_SUPPORTED, maxMemberCount);
            originator = mOriginator;
        }

        final UUID twincodeInboundId = originator != null ? originator.getTwincodeInboundId() : null;
        if (twincodeInboundId == null) {
            return;
        }
        mPeerCallService.joinCallRoom(requestId, callRoomId, twincodeInboundId, getConnectionIds());
    }

    /**
     * Prepare to join the call room.  We only record the call room id so that we can join the
     * call room when the incoming call is accepted.
     *
     * @param callRoomId the call room to join once the call is accepted.
     * @param maxMemberCount the number of participants supported by the callroom.
     */
    synchronized void joinCallRoom(@NonNull UUID callRoomId, int maxMemberCount) {
        if (DEBUG) {
            Log.d(LOG_TAG, "joinCallRoom: callRoomId=" + callRoomId);
        }

        mState |= ConnectionOperation.CREATE_CALL_ROOM | ConnectionOperation.CREATE_CALL_ROOM_DONE;
        mCallRoomId = callRoomId;
        mMaxMemberCount = Math.min(MAX_MEMBER_UI_SUPPORTED, maxMemberCount);
    }

    /**
     * Create the call room.
     *
     * @param callRoomId the call room id.
     * @param memberId the current member identifier within the call room.
     */
    synchronized void updateCallRoom(@NonNull UUID callRoomId, @NonNull String memberId,
                                     @Nullable List<PeerCallService.MemberInfo> members) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateCallRoom callRoomId=" + callRoomId
                    + " memberId=" + memberId);
        }

        mState |= ConnectionOperation.CREATE_CALL_ROOM | ConnectionOperation.CREATE_CALL_ROOM_DONE;
        mCallRoomId = callRoomId;
        mCallRoomMemberId = memberId;
    }

    synchronized void updateCallRoom(@NonNull UUID callRoomId, @NonNull String memberId,
                                     @Nullable List<PeerCallService.MemberInfo> members, int maxMemberCount) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updateCallRoom callRoomId=" + callRoomId
                    + " memberId=" + memberId + " maxMemberCount=" + maxMemberCount);
        }

        updateCallRoom(callRoomId, memberId, members);
        mMaxMemberCount = Math.min(MAX_MEMBER_UI_SUPPORTED, maxMemberCount);
    }

    void setPendingChangeStateConnection(@NonNull CallConnection connection) {
        mPendingChangeStateConnectionId = connection.getPeerConnectionId();
    }

    boolean isTransferReady() {
        return mPendingPrepareTransfers.isEmpty();
    }

    /**
     * Remove the peer connection and release the resources allocated for it (remote renderer).
     *
     * @param callConnection the peer connection to remove.
     * @return true if the call has no peer connection.
     */
    synchronized boolean remove(@NonNull CallConnection callConnection, @NonNull TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "remove callParticipant=" + callConnection + " terminateReason=" + terminateReason);
        }

        mPeers.remove(callConnection);

        final boolean empty = mPeers.isEmpty();
        if (empty) {
            mTerminateReason = terminateReason;
            stopStreaming(false);
        }
        List<CallParticipant> releasedParticipants = callConnection.release(terminateReason);

        if (terminateReason != TerminateReason.TRANSFER_DONE) {
            onRemoveParticipants(releasedParticipants);
        }

        return empty;
    }

    synchronized void addIncomingGroupCallConnection(@NonNull CallConnection connection) {
        mIncomingGroupCallConnections.add(connection);
    }

    synchronized Set<CallConnection> getIncomingGroupCallConnections() {
        HashSet<CallConnection> res = new HashSet<>(mIncomingGroupCallConnections);
        mIncomingGroupCallConnections.clear();
        return res;
    }

    /**
     * Release all the resources used by the call participants.
     */
    synchronized void release() {
        if (DEBUG) {
            Log.d(LOG_TAG, "release");
        }

        ThreadUtils.checkIsOnMainThread();

        stopStreaming(false);

        // Release the remote renderer for each peer connection.
        while (!mPeers.isEmpty()) {
            CallConnection callConnection = mPeers.remove(0);

            callConnection.release(TerminateReason.CANCEL);
        }

        if (mTransferFromConnection != null) {
            mTransferFromConnection.release(TerminateReason.CANCEL);
            mTransferFromConnection = null;
        }

        mPendingCallRoomMembers.clear();
        mPendingPrepareTransfers.clear();
        mIncomingGroupCallConnections.clear();
        mPendingChangeStateConnectionId = null;
        mTransferToMemberId = null;
        transferDirection = null;
        if (mLocalRenderer != null) {
            ViewParent viewParent = mLocalRenderer.getParent();
            if (viewParent != null) {
                ((ViewGroup) viewParent).removeView(mLocalRenderer);
            }
            mLocalRenderer.release();
            mLocalRenderer = null;
        }
    }

    /**
     * @return true if the new participant is allowed to automatically join an active call.
     */
    boolean autoAcceptNewParticipant(@NonNull UUID newParticipantOriginatorId, @Nullable UUID groupId) {
        final Originator originator;
        final UUID callGroupId;
        synchronized (this) {
            originator = mOriginator;
            callGroupId = mGroupId;
        }

        if (originator == null) {
            return false;
        }

        if (originator instanceof CallReceiver) {
            // We're in a Call Receiver call-room, and a new participant is (re-)joining
            if (originator.isGroup() && mOriginatorId.equals(newParticipantOriginatorId)) {
                return true;
            }
            // This is a transfer link, accept it.
            return ((CallReceiver) originator).isTransfer();
        }

        // Return true if we're in a Group call-room, and a member of the group is (re-)joining
        return callGroupId != null && callGroupId.equals(groupId);
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    public boolean isTelecomSupported() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isTelecomSupported");
        }

        return FeatureUtils.isTelecomSupported(getContext()) && !mTelecomFailed;
    }

    public boolean isIncoming() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isIncoming");
        }

        return mIncomingCall;
    }

    public void sendPrepareTransfer() {
        // We're the participant which will be transferred,
        // and the connection with the transfer target is established ->
        // send PrepareTransferIQ to other participant(s)
        for (CallConnection connection : getConnections()) {
            if (!Boolean.TRUE.equals(connection.isTransfer())) {
                connection.sendPrepareTransferIQ();
                synchronized (this) {
                    mPendingPrepareTransfers.add(connection.getPeerConnectionId());
                }
            }
        }
    }

    public enum TransferDirection {
        /**
         * The call is being transferred from this device to the browser.
         */
        TO_BROWSER,
        /**
         * The call is being transferred from the browser to this device.
         */
        TO_DEVICE
    }
}
