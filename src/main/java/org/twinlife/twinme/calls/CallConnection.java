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

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService.Descriptor;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ConversationService.GeolocationDescriptor;
import org.twinlife.twinlife.PeerConnectionService;
import org.twinlife.twinlife.TerminateReason;
import org.twinlife.twinlife.PeerConnectionService.ConnectionState;
import org.twinlife.twinlife.PeerConnectionService.StatType;
import org.twinlife.twinlife.SerializerFactory;
import org.twinlife.twinlife.conversation.ConversationHandler;
import org.twinlife.twinlife.util.BinaryPacketIQ;
import org.twinlife.twinlife.util.Version;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.android.BuildConfig;
import org.twinlife.twinme.audio.AudioDevice;
import org.twinlife.twinme.calls.keycheck.KeyCheckInitiateIQ;
import org.twinlife.twinme.calls.keycheck.OnKeyCheckInitiateIQ;
import org.twinlife.twinme.calls.keycheck.TerminateKeyCheckIQ;
import org.twinlife.twinme.calls.keycheck.TwincodeUriIQ;
import org.twinlife.twinme.calls.keycheck.WordCheckIQ;
import org.twinlife.twinme.calls.keycheck.WordCheckResult;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.calls.streaming.StreamPlayer;
import org.twinlife.twinme.calls.streaming.StreamPlayerImpl;
import org.twinlife.twinme.calls.streaming.StreamerImpl;
import org.twinlife.twinme.calls.streaming.StreamingControlIQ;
import org.twinlife.twinme.calls.streaming.StreamingDataIQ;
import org.twinlife.twinme.calls.streaming.StreamingInfoIQ;
import org.twinlife.twinme.calls.streaming.StreamingRequestIQ;
import org.twinlife.twinme.calls.streaming.StreamingStatus;
import org.twinlife.twinme.models.Zoomable;
import org.twinlife.twinme.utils.CommonUtils;
import org.webrtc.AudioTrack;
import org.webrtc.EglBase;
import org.webrtc.MediaStreamTrack;
import org.webrtc.RtpTransceiver.RtpTransceiverDirection;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.twinlife.twinme.calls.streaming.StreamingControlIQ.IQ_STREAMING_CONTROL_SERIALIZER;
import static org.twinlife.twinme.calls.streaming.StreamingDataIQ.IQ_STREAMING_DATA_SERIALIZER;
import static org.twinlife.twinme.calls.streaming.StreamingInfoIQ.IQ_STREAMING_INFO_SERIALIZER;
import static org.twinlife.twinme.calls.streaming.StreamingRequestIQ.IQ_STREAMING_REQUEST_SERIALIZER;

/**
 * A P2P call connection in an Audio or Video call.
 * <p>
 * The call connection can have one or several participant depending on the target it is connected to.
 * If it is connected to another device, there is only one participant.  If it is connected to a SFU,
 * there could be several participants.
 * <p>
 * Calls are associated with a callId which allow to accept/hold/terminate the call.
 * The callId can be associated with one or several peer connection when the call is a meshed P2P group call.
 */
public final class CallConnection extends ConversationHandler {
    private static final String LOG_TAG = "CallConnection";
    private static final boolean DEBUG = false;

    private static final String DATA_VERSION = "CallService:1.5.0";
    private static final String CAP_STREAM = "stream";
    private static final String CAP_TRANSFER = "transfer";
    private static final String CAP_MESSAGE = "message";
    private static final String CAP_GEOLOCATION = "geoloc";
    private static final String CAP_ZOOMABLE = "zoomable";  // Remote control of camera zoom is allowed.
    private static final String CAP_ZOOM_ASK = "zoom-ask";  // Allowed only after user confirmation.

    private static final UUID PARTICIPANT_INFO_SCHEMA_ID = UUID.fromString("a8aa7e0d-c495-4565-89bb-0c5462b54dd0");
    private static final UUID PARTICIPANT_TRANSFER_SCHEMA_ID = UUID.fromString("800fd629-83c4-4d42-8910-1b4256d19eb8");
    private static final UUID TRANSFER_DONE_SCHEMA_ID = UUID.fromString("641bf1f6-ebbf-4501-9151-76abc1b9adad");
    private static final UUID PREPARE_TRANSFER_SCHEMA_ID = UUID.fromString("9eaa4ad1-3404-4bcc-875d-dc75c748e188");
    private static final UUID ON_PREPARE_TRANSFER_SCHEMA_ID = UUID.fromString("a17516a2-4bd2-4284-9535-726b6eb1a211");

    private static final UUID HOLD_CALL_SCHEMA_ID = UUID.fromString("f373eaf0-79ef-4091-8179-de622afce358");
    private static final UUID RESUME_CALL_SCHEMA_ID = UUID.fromString("70ea071a-48f7-41e9-ace5-2c3616f8abf5");

    private static final UUID SCREEN_SHARING_ON_SCHEMA_ID = UUID.fromString("c52596ad-23b4-45fe-bba1-5992e7aa872b");
    private static final UUID SCREEN_SHARING_OFF_SCHEMA_ID = UUID.fromString("b35971e1-b4ae-45c1-a0a8-73cf2a78ee3c");

    private static final UUID CAMERA_CONTROL_SCHEMA_ID = UUID.fromString("6512ff06-7c18-4de4-8760-61b87b9169a5");
    private static final UUID CAMERA_RESPONSE_SCHEMA_ID = UUID.fromString("c9ba7001-c32d-4545-bdfb-e80ff0db21aa");

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_PARTICIPANT_INFO_SERIALIZER = ParticipantInfoIQ.createSerializer(PARTICIPANT_INFO_SCHEMA_ID, 1);

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_PARTICIPANT_TRANSFER_SERIALIZER = ParticipantTransferIQ.createSerializer(PARTICIPANT_TRANSFER_SCHEMA_ID, 1);

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_TRANSFER_DONE_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(TRANSFER_DONE_SCHEMA_ID, 1);
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_PREPARE_TRANSFER_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(PREPARE_TRANSFER_SCHEMA_ID, 1);

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_ON_PREPARE_TRANSFER_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(ON_PREPARE_TRANSFER_SCHEMA_ID, 1);

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_HOLD_CALL_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(HOLD_CALL_SCHEMA_ID, 1);
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_RESUME_CALL_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(RESUME_CALL_SCHEMA_ID, 1);

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_KEY_CHECK_INITIATE_SERIALIZER = KeyCheckInitiateIQ.IQ_KEY_CHECK_INITIATE_SERIALIZER;
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_ON_KEY_CHECK_INITIATE_SERIALIZER = OnKeyCheckInitiateIQ.IQ_ON_KEY_CHECK_INITIATE_SERIALIZER;
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_WORD_CHECK_SERIALIZER = WordCheckIQ.IQ_WORD_CHECK_SERIALIZER;
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_TERMINATE_KEY_CHECK_SERIALIZER = TerminateKeyCheckIQ.IQ_TERMINATE_KEY_CHECK_SERIALIZER;
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_TWINCODE_URI_SERIALIZER = TwincodeUriIQ.IQ_TWINCODE_URI_SERIALIZER;

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_SCREEN_SHARING_ON_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(SCREEN_SHARING_ON_SCHEMA_ID, 1);
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_SCREEN_SHARING_OFF_SERIALIZER = BinaryPacketIQ.createDefaultSerializer(SCREEN_SHARING_OFF_SCHEMA_ID, 1);

    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_CAMERA_CONTROL_SERIALIZER = CameraControlIQ.createSerializer(CAMERA_CONTROL_SCHEMA_ID, 1);
    private static final BinaryPacketIQ.BinaryPacketIQSerializer IQ_CAMERA_RESPONSE_SERIALIZER = CameraResponseIQ.createSerializer(CAMERA_RESPONSE_SCHEMA_ID, 1);

    private int mState;
    @NonNull
    private volatile ConnectionState mConnectionState;

    @NonNull
    private final Map<UUID, CallParticipant> mParticipants;
    @NonNull
    private CallState mCall;
    @NonNull
    private final CallParticipant mMainParticipant;
    @Nullable
    private Originator mOriginator;
    @Nullable
    private UUID mPeerTwincodeOutboundId;

    @Nullable
    private String mVideoTrackId;
    @Nullable
    private String mAudioTrackId;
    private volatile long mConnectionStartTime;
    @Nullable
    private ScheduledFuture<?> mTimer;
    private boolean mVideo;
    private volatile boolean mPeerConnected;

    // The P2P implementation version passed during session-init/session-accept.
    // The value is known when we received the session-initiate or session-accept.
    @Nullable
    private volatile Version mPeerVersion;
    @NonNull
    volatile CallStatus mStatus;
    @Nullable
    private String mCallMemberId;
    /**
     * If not null, this connection's main participant is currently in transfer towards this memberId.
     */
    @Nullable
    private String mTransferToMemberId = null;
    // The stream player to use when we are receiving a media stream from the peer.
    @Nullable
    private StreamPlayerImpl mMediaStream;
    @NonNull
    private volatile StreamingStatus mStreamingStatus;
    @Nullable
    private volatile Boolean mMessageSupported;
    @Nullable
    private volatile Boolean mGeolocSupported;
    @Nullable
    private volatile Zoomable mZoomable;
    private volatile boolean mRemoteControlGranted;

    /**
     * Set to true when we receive a invite-call-room IQ.
     * When re-joining a group call, we'll receive this IQ before the session-accept IQ.
     * <p>
     * Used to check whether we're the one creating the call room: if true, we're joining an existing
     * call room and we must not create a new call room, nor invite this peer (since it's already in the call room).
     */
    private boolean invited = false;

    /**
     * Get the remote renderer for this peer connection.
     *
     * @return the remote renderer or null if this peer connection has no video.
     */
    @Nullable
    public SurfaceViewRenderer getRemoteRenderer() {

        return mMainParticipant.getRemoteRenderer();
    }

    /**
     * Get the main call participant.
     *
     * @return the main participant of this call.
     */
    @NonNull
    public CallParticipant getMainParticipant() {

        return mMainParticipant;
    }

    public boolean isVideo() {

        return mVideo;
    }

    /**
     * Get the stream player that is playing media streams sent by the peer.
     *
     * @return the stream player or null.
     */
    @Nullable
    public StreamPlayer getStreamPlayer() {

        return mMediaStream;
    }

    /**
     * Returns true if the peer is connected.
     *
     * @return true if the peer is connected.
     */
    public boolean isConnected() {

        return mPeerConnected;
    }

    /**
     * Get the current connection state.
     *
     * @return the current connection state.
     */
    @NonNull
    public CallStatus getStatus() {

        return mStatus;
    }

    @NonNull
    public CallState getCall() {

        return mCall;
    }

    @NonNull
    public ConnectionState getConnectionState() {

        return mConnectionState;
    }

    /**
     * Get the connection start time.
     *
     * @return the time when the connection reached CONNECTED state for the first time.
     */
    public long getConnectionStartTime() {

        return mConnectionStartTime;
    }

    /**
     * Check if this connection supports P2P group calls.
     *
     * @return NULL if we don't know, TRUE if P2P group calls are supported.
     */
    @Nullable
    public Boolean isGroupSupported() {

        final Version v = mPeerVersion;
        if (v == null) {
            return null;
        }

        return v.major >= 2;
    }

    /**
     * Check if this connection supports the sending message during a call.
     *
     * @return NULL if we don't know, TRUE if sending messages is supported.
     */
    @Nullable
    public Boolean isMessageSupported() {

        return mMessageSupported;
    }

    /**
     * Check if this connection supports the sending geolocation during a call.
     *
     * @return NULL if we don't know, TRUE if sending geolocation is supported.
     */
    @Nullable
    public Boolean isGeolocSupported() {

        return mGeolocSupported;
    }

    /**
     * Check if this connection supports the schedule capability.
     *
     * @return NULL if we don't know, TRUE if schedule capability is supported.
     */
    @Nullable
    public Boolean isScheduleSupported() {

        final Version v = mPeerVersion;
        if (v == null) {
            return null;
        }
        // Schedule capability was introduced in v2.1.0.
        return v.major >= 3 || (v.major == 2 && v.minor >= 1);
    }

    /**
     * Check if this connection was created to perform a call transfer.
     *
     * @return NULL if we don't know, TRUE if the originator is a Transfer Call Receiver.
     */
    @Nullable
    public Boolean isTransfer(){
        if(mOriginator == null){
            return null;
        }

        return mOriginator.getType() == Originator.Type.CALL_RECEIVER && ((CallReceiver)mOriginator).isTransfer();
    }

    /**
     * Indicates whether we can take control of the peer camera and zoom on it remotely.
     * @return the zoomable status for this participant.
     */
    @NonNull
    public Zoomable isZoomable() {
        final Zoomable result = mZoomable;
        return result == null ? Zoomable.NEVER : result;
    }

    /**
     * Indicates whether we grant remote control to the peer.
     * @return true if we allow the peer to take control of our camera.
     */
    public boolean isRemoteControlGranted() {

        return mRemoteControlGranted;
    }

    /**
     * Get the audio streaming status for the peer connection.
     *
     * @return UNKNOWN if we don't know, NOT_AVAILABLE if the peer does not support streaming,
     * READY when the peer supports streaming, PLAYING when it supports streaming and is actually streaming,
     * UNSUPPORTED if the current stream is not supported and ERROR if the current stream has errors.
     */
    @NonNull
    public StreamingStatus getStreamingStatus() {

        return mStreamingStatus;
    }

    /**
     * The peer twincode outbound id that is used for the P2P connection.
     *
     * @return the peer twincode outbound id if we know it.
     */
    @Nullable
    public UUID getPeerTwincodeOutboundId() {

        return mPeerTwincodeOutboundId;
    }

    public Originator getOriginator(){
        return mOriginator;
    }

    /*
     * Internal methods.
     */

    @NonNull
    @Override
    public PeerConnectionService.DataChannelConfiguration getConfiguration(@NonNull UUID peerConnectionId,
                                                                           @NonNull PeerConnectionService.SdpEncryptionStatus encryptionStatus) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getConfiguration: peerConnectionId=" + peerConnectionId);
        }

        return new PeerConnectionService.DataChannelConfiguration(getDataVersion(), false);
    }

    @Override
    public void onDataChannelOpen(@NonNull UUID peerConnectionId, @Nullable String peerVersion, boolean leadingPadding) {
        if (DEBUG) {
            Log.d(LOG_TAG, "Data channel opened " + peerConnectionId + " v=" + peerVersion);
        }

        super.onDataChannelOpen(peerConnectionId, peerVersion, leadingPadding);
        if (peerVersion != null) {

            // CallService:<version>:<capability>,...,<capability>.
            final String[] items = peerVersion.split("[:,]");
            StreamingStatus status = StreamingStatus.NOT_AVAILABLE;
            boolean messageSupported = false;
            boolean geolocSupported = false;
            Zoomable zoomable = Zoomable.NEVER;
            if (items.length >= 3) {
                for (int i = items.length; --i >= 1; ) {
                    if (CAP_STREAM.equals(items[i])) {
                        status = StreamingStatus.READY;
                    } else if (CAP_MESSAGE.equals(items[i])) {
                        messageSupported = true;
                    } else if (CAP_GEOLOCATION.equals(items[i])) {
                        geolocSupported = true;
                    } else if (CAP_ZOOMABLE.equals(items[i])) {
                        zoomable = Zoomable.ALLOW;
                    } else if (CAP_ZOOM_ASK.equals(items[i])) {
                        zoomable = Zoomable.ASK;
                    }
                }
            }
            mStreamingStatus = status;
            mMessageSupported = messageSupported;
            mGeolocSupported = geolocSupported;
            mZoomable = zoomable;
        }

        // If this is a P2P within a call room, send the peer our identification.
        if (mCall.getCallRoomId() != null) {
            sendParticipantInfoIQ();
        }
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_CONNECTED);
    }

    @Override
    public void onPopDescriptor(@NonNull Descriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPopDescriptor descriptor=" + descriptor);
        }

        mMainParticipant.updateSender(descriptor.getDescriptorId().twincodeOutboundId);
        mCall.onPopDescriptor(mMainParticipant, descriptor);
    }

    @Override
    public void onUpdateGeolocation(@NonNull GeolocationDescriptor descriptor) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateGeolocation descriptor=" + descriptor);
        }

        mCall.onUpdateGeolocation(mMainParticipant, descriptor);
    }

    @Override
    public void onReadDescriptor(@NonNull DescriptorId descriptorId, long timestamp) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onReadDescriptor descriptorId=" + descriptorId + " timestamp=" + timestamp);
        }
    }

    @Override
    public void onDeleteDescriptor(@NonNull DescriptorId descriptorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteDescriptor descriptorId=" + descriptorId);
        }

        mCall.onDeleteDescriptor(mMainParticipant, descriptorId);
    }

    @Override
    public long newRequestId() {
        if (DEBUG) {
            Log.d(LOG_TAG, "newRequestId");
        }

        return mCall.allocateRequestId();
    }

    /*
     * Package private methods.
     */

    /**
     * Create a new P2P audio/video call connection.
     *
     * @param peerConnectionService the peer connection service.
     * @param serializerFactory     the serializer factory.
     * @param call                  the call that this peer connection belongs.
     * @param peerConnectionId      the peer connection id for an incoming P2P connection.
     * @param callStatus            the initial state.
     * @param memberId              the optional group member identification for the peer.
     */
    CallConnection(@NonNull PeerConnectionService peerConnectionService, @NonNull SerializerFactory serializerFactory,
                   @NonNull CallState call, @Nullable UUID peerConnectionId,
                   @NonNull CallStatus callStatus, @Nullable String memberId) {
        super(peerConnectionService, serializerFactory);

        mCall = call;
        mState = 0;
        mStatus = callStatus;
        mPeerConnectionId = peerConnectionId;
        mPeerConnected = false;
        mConnectionState = ConnectionState.INIT;
        mConnectionStartTime = 0;
        mPeerVersion = null;
        mVideo = callStatus.isVideo();
        mCallMemberId = memberId;
        mParticipants = new HashMap<>();
        mMainParticipant = new CallParticipant(this, call.allocateParticipantId());
        if (peerConnectionId != null) {
            mParticipants.put(peerConnectionId, mMainParticipant);
            mCall.onAddParticipant(mMainParticipant);
        }

        addListener(IQ_PARTICIPANT_INFO_SERIALIZER, this::onParticipantInfoIQ);
        addListener(IQ_PARTICIPANT_TRANSFER_SERIALIZER, this::onParticipantTransferIQ);
        addListener(IQ_TRANSFER_DONE_SERIALIZER, this::onTransferDoneIQ);
        addListener(IQ_PREPARE_TRANSFER_SERIALIZER, this::onPrepareTransferIQ);
        addListener(IQ_ON_PREPARE_TRANSFER_SERIALIZER, this::onOnPrepareTransferIQ);

        addListener(IQ_STREAMING_INFO_SERIALIZER, this::onStreamingInfoIQ);
        addListener(IQ_STREAMING_CONTROL_SERIALIZER, this::onStreamingControlIQ);
        addListener(IQ_STREAMING_DATA_SERIALIZER, this::onStreamingDataIQ);
        addListener(IQ_STREAMING_REQUEST_SERIALIZER, this::onStreamingRequestIQ);

        addListener(IQ_HOLD_CALL_SERIALIZER, this::onHoldCallIQ);
        addListener(IQ_RESUME_CALL_SERIALIZER, this::onResumeCallIQ);

        addListener(IQ_KEY_CHECK_INITIATE_SERIALIZER, this::onKeyCheckInitiateIQ);
        addListener(IQ_ON_KEY_CHECK_INITIATE_SERIALIZER, this::onOnKeyCheckInitiateIQ);
        addListener(IQ_WORD_CHECK_SERIALIZER, this::onWordCheckIQ);
        addListener(IQ_TERMINATE_KEY_CHECK_SERIALIZER, this::onTerminateKeyCheckIQ);
        addListener(IQ_TWINCODE_URI_SERIALIZER, this::onTwincodeUriIQ);

        addListener(IQ_SCREEN_SHARING_ON_SERIALIZER, this::onScreenSharingOnIQ);
        addListener(IQ_SCREEN_SHARING_OFF_SERIALIZER, this::onScreenSharingOffIQ);

        addListener(IQ_CAMERA_CONTROL_SERIALIZER, this::onCameraControlIQ);
        addListener(IQ_CAMERA_RESPONSE_SERIALIZER, this::onCameraResponseIQ);

        mStreamingStatus = StreamingStatus.UNKNOWN;
    }

    void setCall(@NonNull CallState call) {
        mCall = call;
        mCall.addPeerConnection(this);
        mCall.onAddParticipant(mMainParticipant);
    }

    @Nullable
    String getCallMemberId() {

        return mCallMemberId;
    }

    void setCallMemberId(@NonNull String memberId) {

        mCallMemberId = memberId;
    }

    @Nullable
    public String getTransferToMemberId() {
        return mTransferToMemberId;
    }

    public void setTransferToMemberId(@Nullable String transferToMemberId) {
        mTransferToMemberId = transferToMemberId;
    }

    public boolean isInvited() {
        return invited;
    }

    public void setInvited(boolean invited) {
        this.invited = invited;
    }

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

    synchronized boolean isDoneOperation(int operation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isDoneOperation: operation=" + operation);
        }

        return (mState & operation) != 0;
    }

    synchronized void setOriginator(@NonNull Originator originator, @Nullable Bitmap avatar, @Nullable Bitmap identityAvatar, @Nullable Bitmap groupAvatar) {

        mPeerTwincodeOutboundId = originator.getPeerTwincodeOutboundId();

        mOriginator = originator;
        mMainParticipant.setInformation(originator.getName(), originator.getIdentityDescription(), avatar, groupAvatar);
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_IDENTITY);
    }

    synchronized void setTimer(@NonNull ScheduledExecutorService executor, @NonNull TwinmeContext.Consumer<CallConnection> command, int timeout, @NonNull CallStatus callStatus) {

        if (mTimer != null) {
            mTimer.cancel(false);
        }

        mTimer = executor.schedule(() -> command.accept(this), timeout, TimeUnit.SECONDS);
        mStatus = callStatus;
    }

    void setPeerTwincodeOutboundId(@NonNull UUID peerTwincodeOutboundId) {

        mPeerTwincodeOutboundId = peerTwincodeOutboundId;
    }

    void setPeerVersion(@NonNull Version version) {

        mPeerVersion = version;
    }

    /**
     * Set the connection state for this P2P connection.
     *
     * @param state the new connection state.
     * @return true if we are now connected.
     */
    synchronized boolean updateConnectionState(@NonNull ConnectionState state) {

        mConnectionState = state;

        if (state != ConnectionState.CONNECTED) {

            return false;
        }

        if (mTimer != null) {
            mTimer.cancel(false);
            mTimer = null;
        }

        if (mConnectionStartTime == 0) {
            mConnectionStartTime = SystemClock.elapsedRealtime();
        }

        mPeerConnected = true;
        mStatus = mStatus.toActive();
        return true;
    }

    private boolean sendAudio(){
        return getCall().isAudioSourceOn();
    }

    private boolean sendVideo(){
        return getCall().isVideoSourceOn();
    }

    void initSources(@Nullable EglBase.Context eglBaseContext, @NonNull CallStatus callStatus) {

        synchronized (this) {
            if (mStatus != callStatus) {
                mStatus = callStatus;
                mVideo = callStatus.isVideo() && eglBaseContext != null;
            }
        }

        initSources();
    }

    void initSources() {

        // The initSources can be made only when the incoming peer connection is created.
        if (mPeerConnectionId != null && isDoneOperation(ConnectionOperation.CREATED_PEER_CONNECTION)) {
            mPeerConnectionService.initSources(mPeerConnectionId, sendAudio(), sendVideo());
        }
    }

    void setAudioDirection(@NonNull RtpTransceiverDirection direction) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setAudioDirection: direction=" + direction);
        }

        if (mPeerConnectionId != null) {
            mPeerConnectionService.setAudioDirection(mPeerConnectionId, direction);
        }
    }

    void setVideoDirection(@NonNull RtpTransceiverDirection direction) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setVideoDirection: direction=" + direction);
        }

        if (mPeerConnectionId != null) {
            mPeerConnectionService.setVideoDirection(mPeerConnectionId, direction);
        }
    }

    void terminate(@NonNull TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "terminate: terminateReason=" + terminateReason);
        }

        if (getCall().getStatus() != CallStatus.OUTGOING_CALL
                && terminateReason == TerminateReason.SCHEDULE
                && !Boolean.TRUE.equals(isScheduleSupported())){
            // we're rejecting the incoming call because of the schedule,
            // but the peer doesn't support TerminateReason.SCHEDULE
            terminateReason = TerminateReason.NOT_AUTHORIZED;
        }

        if (mPeerConnectionId != null) {
            mPeerConnectionService.terminatePeerConnection(mPeerConnectionId, terminateReason);
        }
    }

    void onCreateIncomingPeerConnection(@NonNull final UUID peerConnectionId, @Nullable EglBase.Context eglBaseContext) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateIncomingPeerConnection: peerConnectionId=" + peerConnectionId);
        }

        synchronized (this) {
            if ((mState & ConnectionOperation.CREATE_INCOMING_PEER_CONNECTION_DONE) != 0) {

                return;
            }
            mState |= ConnectionOperation.CREATE_INCOMING_PEER_CONNECTION_DONE | ConnectionOperation.CREATED_PEER_CONNECTION;

            setPeerConnectionId(peerConnectionId);
        }

        mPeerConnectionService.initSources(peerConnectionId, sendAudio(), eglBaseContext != null && sendVideo());
    }

    void onCreateOutgoingPeerConnection(@NonNull final UUID peerConnectionId, @Nullable EglBase.Context eglBaseContext) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateOutgoingPeerConnection: peerConnectionId=" + peerConnectionId);
        }

        synchronized (this) {
            if ((mState & ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION_DONE) != 0) {

                return;
            }
            mState |= ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION_DONE | ConnectionOperation.CREATED_PEER_CONNECTION;

            setPeerConnectionId(peerConnectionId);
        }

        // A race can occur while we are creating the outgoing peer connection and the call is terminated.
        // In that case, we could have called `terminate()` but the peer connection id was not known
        // and the terminate for that CallConnection is ignored and remains, then the P2P connection
        // establishes and we have a live P2P that is not attached to any valid CallState.
        // Important note: we must release the lock on CallConnection when calling mCall.isTerminated()
        // otherwise a deadlock can occur.
        if (mCall.isTerminated()) {
            mPeerConnectionService.terminatePeerConnection(peerConnectionId, TerminateReason.CANCEL);
        } else {
            mPeerConnectionService.initSources(peerConnectionId, sendAudio(), eglBaseContext != null && sendVideo());
        }
    }

    synchronized void addRemoteMediaStreamTrack(@NonNull final MediaStreamTrack mediaStream) {

        CallParticipant participant = getMainParticipant();

        if (mediaStream instanceof VideoTrack) {
            try {
                // Be careful that the MediaStreamTrack could have been disposed!
                mVideoTrackId = mediaStream.id();
                participant.setCameraMute(false);

                SurfaceViewRenderer remoteRenderer = participant.getRemoteRenderer();
                if (remoteRenderer == null) {

                    return;
                }

                VideoTrack videoTrack = (VideoTrack) mediaStream;
                videoTrack.addSink(remoteRenderer);
                mCall.onEventParticipant(participant, CallParticipantEvent.EVENT_VIDEO_ON);

            } catch (IllegalStateException ex) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "VideoTrack has been disposed");

                }
            }
        } else if (mediaStream instanceof AudioTrack) {
            // Note: we have to be careful that the media stream track can have been disposed.
            // This occurs when the user quickly enable/disable some audio/video and the peer
            // is slow at handling the twinlife executor's thread.
            try {
                mAudioTrackId = mediaStream.id();
                participant.setMicrophoneMute(false);
                mCall.onEventParticipant(participant, CallParticipantEvent.EVENT_AUDIO_ON);

            } catch (IllegalStateException ex) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "AudioTrack has been disposed");
                }
            }
        }
    }

    /**
     * The track id was removed by the peer, update and if it was a known track return a message to send.
     *
     * @param trackId the track id that was removed.
     */
    synchronized void removeRemoteTrack(@NonNull String trackId) {

        CallParticipant participant = getMainParticipant();
        if (trackId.equals(mVideoTrackId)) {
            mVideoTrackId = null;
            participant.setCameraMute(true);
            mCall.onEventParticipant(participant, CallParticipantEvent.EVENT_VIDEO_OFF);

        } else if (trackId.equals(mAudioTrackId)) {
            mAudioTrackId = null;
            participant.setMicrophoneMute(true);
            mCall.onEventParticipant(participant, CallParticipantEvent.EVENT_AUDIO_OFF);

        }
    }

    /**
     * Set the peer connection id for this peer connection.
     *
     * @param peerConnectionId the peer connection id.
     */
    @Override
    public synchronized void setPeerConnectionId(@NonNull UUID peerConnectionId) {

        if (mPeerConnectionId == null) {
            mPeerConnectionId = peerConnectionId;
            mParticipants.put(peerConnectionId, mMainParticipant);
            mCall.onAddParticipant(mMainParticipant);
        }
    }


    /*
     * Telecom API callbacks.
     */

    public void showIncomingCallUi() {
        if (DEBUG) {
            Log.d(LOG_TAG, "showIncomingCallUi");
        }

        mCall.showIncomingCallUi(this);
    }

    public void onCallAudioStateChanged(@NonNull AudioDevice activeDevice, @NonNull Set<AudioDevice> availableDevices) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCallAudioStateChanged: activeDevice=" + activeDevice + " availableDevices=" + availableDevices);
        }

        mCall.onCallAudioStateChanged(activeDevice, availableDevices);
    }

    synchronized void setDeviceRinging() {
        mConnectionState = ConnectionState.RINGING;
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_RINGING);
    }

    /**
     * Get the list of participants in this P2P connection.
     *
     * @param into the list into which participants are returned.
     */
    synchronized void getParticipants(@NonNull List<CallParticipant> into) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getParticipants: into=" + into);
        }

        into.addAll(mParticipants.values());
    }

    synchronized void putState(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "putState: intent=" + intent);
        }

        intent.putExtra(CallService.CALL_SERVICE_CONNECTION_STATE, mConnectionState);
    }

    /**
     * Release the remote renderer when the connection is destroyed.
     *
     * @param terminateReason the P2P connection terminate reason.
     * @return the list of participants that have been released.
     */
    @NonNull
    synchronized List<CallParticipant> release(@NonNull TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "release: terminateReason=" + terminateReason);
        }

        mStatus = CallStatus.TERMINATED;

        if (mTimer != null) {
            mTimer.cancel(false);
            mTimer = null;
        }

        if (mMediaStream != null) {
            mMediaStream.stop(false);
            mMediaStream = null;
        }

        // Release the remote renderer for each peer connection.
        final List<CallParticipant> participants = new ArrayList<>(mParticipants.values());
        if (participants.isEmpty()) {
            participants.add(mMainParticipant);
        }
        for (CallParticipant participant : participants) {
            participant.release();
        }

        return participants;
    }

    /**
     * Get the data channel version string to send to the peer.
     *
     * @return the data channel version to send to the peer for the data channel.
     */
    @NonNull
    private String getDataVersion() {

        final Zoomable zoomable = mCall.isZoomableByPeer();
        final String zoomCapability;
        switch (zoomable) {
            case ASK:
                zoomCapability = "," + CAP_ZOOM_ASK;
                break;
            case ALLOW:
                zoomCapability = "," + CAP_ZOOMABLE;
                break;
            case NEVER:
            default:
                zoomCapability = "";
                break;
        }
        if (BuildConfig.IS_SKRED && CommonUtils.isGooglePlayServicesAvailable(getCall().getContext())) {
            return DATA_VERSION + ":" + CAP_STREAM + "," + CAP_TRANSFER + "," + CAP_MESSAGE + "," + CAP_GEOLOCATION + zoomCapability;
        } else {
            return DATA_VERSION + ":" + CAP_STREAM + "," + CAP_TRANSFER + "," + CAP_MESSAGE + zoomCapability;
        }
    }

    private void sendParticipantInfoIQ() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendParticipantInfoIQ");
        }

        final Originator originator = mCall.getOriginator();
        if (originator == null || mPeerConnectionId == null) {

            return;
        }

        final String name = originator.getIdentityName();
        if (name == null) {

            return;
        }

        final Bitmap avatar = mCall.getIdentityAvatar();
        final String description = originator.getIdentityDescription();
        final byte[] thumbnailData;
        if (avatar != null) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            if (avatar.hasAlpha()) {
                avatar.compress(Bitmap.CompressFormat.PNG, 0, byteArrayOutputStream);
            } else {
                avatar.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream);
            }
            thumbnailData = byteArrayOutputStream.toByteArray();
        } else {
            thumbnailData = null;
        }

        String memberId = mCall.getCallRoomMemberId() != null ? mCall.getCallRoomMemberId() : "";
        final ParticipantInfoIQ iq = new ParticipantInfoIQ(IQ_PARTICIPANT_INFO_SERIALIZER, mCall.allocateRequestId(),
                    memberId, name, description, thumbnailData);
        sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
    }

    /**
     * Handle the ParticipantInfoIQ packet.
     *
     * @param iq the participant info iq.
     */
    private void onParticipantInfoIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onParticipantInfoIQ: iq=" + iq);
        }

        if (!(iq instanceof ParticipantInfoIQ)) {
            return;
        }

        if (mMainParticipant.getTransferredFromParticipantId() != null) {
            // The participant is a transfer target, ignore the info
            // because we already copied it from the transferred participant.
            return;
        }

        ParticipantInfoIQ participantInfoIQ = (ParticipantInfoIQ) iq;

        Bitmap avatar = null;
        if (participantInfoIQ.thumbnailData != null) {
            avatar = BitmapFactory.decodeByteArray(participantInfoIQ.thumbnailData, 0, participantInfoIQ.thumbnailData.length);
        }

        // Click-to-call callers can set an avatar but it's not mandatory,
        // so we use the CallReceiver's avatar if we didn't receive one from the caller.
        final Originator originator = mCall.getOriginator();
        if (avatar == null && originator != null && originator.getType() == Originator.Type.CALL_RECEIVER) {
            avatar = mCall.getIdentityAvatar();
        }

        Bitmap groupAvatar = mCall.getGroupAvatar();

        mMainParticipant.setInformation(participantInfoIQ.name, participantInfoIQ.description, avatar, groupAvatar);

        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_IDENTITY);
    }

    /**
     * Handle the StreamingControlIQ packet (Android >= 6.0).
     *
     * @param iq the streaming control iq.
     */
    private void onStreamingControlIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingControlIQ: iq=" + iq);
        }

        if (!(iq instanceof StreamingControlIQ)) {
            return;
        }

        final StreamingControlIQ streamingControlIQ = (StreamingControlIQ) iq;
        final StreamPlayerImpl mediaStream;
        StreamPlayerImpl stopMediaStream = null;
        switch (streamingControlIQ.control) {
            case START_AUDIO_STREAMING:
                mediaStream = new StreamPlayerImpl(streamingControlIQ.ident, streamingControlIQ.length, false,
                        mCall, this, null);
                synchronized (this) {
                    stopMediaStream = mMediaStream;
                    mMediaStream = mediaStream;
                }
                mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_START);
                break;

            case START_VIDEO_STREAMING:
                mediaStream = new StreamPlayerImpl(streamingControlIQ.ident, streamingControlIQ.length, true,
                        mCall, this, null);
                synchronized (this) {
                    stopMediaStream = mMediaStream;
                    mMediaStream = mediaStream;
                }
                mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_START);
                break;

            case PAUSE_STREAMING:
                synchronized (this) {
                    mediaStream = mMediaStream;
                }
                if (mediaStream != null) {
                    mediaStream.onStreamingControlIQ(streamingControlIQ);
                    mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_PAUSE);
                }
                break;

            case RESUME_STREAMING:
                synchronized (this) {
                    mediaStream = mMediaStream;
                }
                if (mediaStream != null) {
                    mediaStream.onStreamingControlIQ(streamingControlIQ);
                    mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_RESUME);
                }
                break;

            case SEEK_STREAMING:
                synchronized (this) {
                    mediaStream = mMediaStream;
                }
                if (mediaStream != null && streamingControlIQ.length >= 0) {
                    mediaStream.onStreamingControlIQ(streamingControlIQ);
                }
                break;

            case STOP_STREAMING:
                synchronized (this) {
                    stopMediaStream = mMediaStream;
                    mMediaStream = null;
                }
                if (stopMediaStream != null) {
                    mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_STOP);
                }
                break;

            case ASK_PAUSE_STREAMING:
            case ASK_RESUME_STREAMING:
            case ASK_SEEK_STREAMING:
            case ASK_STOP_STREAMING:
            case STREAMING_STATUS_PLAYING:
            case STREAMING_STATUS_PAUSED:
            case STREAMING_STATUS_ERROR:
            case STREAMING_STATUS_UNSUPPORTED:
            case STREAMING_STATUS_READY:
            case STREAMING_STATUS_COMPLETED:
                final StreamerImpl streamer = (StreamerImpl) mCall.getCurrentStreamer();
                if (streamer != null) {
                    streamer.onStreamingControlIQ(this, streamingControlIQ);
                }
                break;
        }

        if (stopMediaStream != null) {
            stopMediaStream.stop(true);
        }
    }

    public void updatePeerStreamingStatus(@NonNull StreamingStatus status) {
        if (DEBUG) {
            Log.d(LOG_TAG, "updatePeerStreamingStatus: status=" + status);
        }

        synchronized (this) {
            if (mStreamingStatus == status) {
                return;
            }

            mStreamingStatus = status;
        }
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_STATUS);
    }

    /**
     * Handle the StreamingInfoIQ packet (Android >= 6.0).
     *
     * @param iq the streaming info iq.
     */
    private void onStreamingInfoIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingInfoIQ: iq=" + iq);
        }

        if (!(iq instanceof StreamingInfoIQ)) {
            return;
        }

        final StreamingInfoIQ streamingInfoIQ = (StreamingInfoIQ) iq;
        final StreamPlayerImpl streamPlayer = mMediaStream;
        if (streamPlayer == null || streamingInfoIQ.ident != streamPlayer.getIdent()) {

            return;
        }

        Bitmap artwork = null;
        if (streamingInfoIQ.artwork != null) {
            artwork = BitmapFactory.decodeByteArray(streamingInfoIQ.artwork, 0, streamingInfoIQ.artwork.length);
        }
        streamPlayer.setInformation(streamingInfoIQ.title, streamingInfoIQ.album, streamingInfoIQ.artist, artwork, streamingInfoIQ.duration);
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_STREAM_INFO);
    }

    /**
     * Handle the StreamingDataIQ packet (Android >= 6.0).
     *
     * @param iq the streaming data iq.
     */
    private void onStreamingDataIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingDataIQ: iq=" + iq);
        }

        if (!(iq instanceof StreamingDataIQ)) {
            return;
        }

        final StreamPlayerImpl mediaStream = mMediaStream;
        if (mediaStream != null) {
            mediaStream.onStreamingDataIQ((StreamingDataIQ) iq);
        }
    }

    /**
     * Handle the StreamingRequestIQ packet (Android >= 6.0).
     *
     * @param iq the streaming data iq.
     */
    private void onStreamingRequestIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStreamingRequestIQ: iq=" + iq);
        }

        if (!(iq instanceof StreamingRequestIQ)) {
            return;
        }

        final StreamerImpl streamer = (StreamerImpl)mCall.getCurrentStreamer();
        if (streamer == null) {

            return;
        }

        streamer.onStreamingRequestIQ(this, (StreamingRequestIQ )iq);
    }

    public void sendParticipantTransferIQ(String memberId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendParticipantTransferIQ: memberId="+memberId);
        }

        final Originator originator = mCall.getOriginator();
        if (originator == null || mPeerConnectionId == null) {

            return;
        }

        try {
            final ParticipantTransferIQ iq = new ParticipantTransferIQ(IQ_PARTICIPANT_TRANSFER_SERIALIZER, mCall.allocateRequestId(),
                    memberId);
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);

        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    /**
     * Handle the ParticipantTransferIQ packet.
     *
     * @param iq the participant transfer iq.
     */
    private void onParticipantTransferIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onParticipantTransferIQ: iq=" + iq);
        }

        if (!(iq instanceof ParticipantTransferIQ)) {
            return;
        }

        ParticipantTransferIQ participantTransferIQ = (ParticipantTransferIQ) iq;

        setTransferToMemberId(participantTransferIQ.memberId);

        mCall.onEventParticipantTransfer(participantTransferIQ.memberId);
    }

    public void sendTransferDoneIQ() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendTransferDoneIQ");
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final TransferDoneIQ iq = new TransferDoneIQ(IQ_TRANSFER_DONE_SERIALIZER, mCall.allocateRequestId());
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    private void onTransferDoneIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTransferDoneIQ: iq=" + iq);
        }

        if (!Boolean.TRUE.equals(isTransfer())) {
            return;
        }
        mCall.onTransferDone();
    }

    public void sendPrepareTransferIQ() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendPrepareTransferIQ");
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final PrepareTransferIQ iq = new PrepareTransferIQ(IQ_PREPARE_TRANSFER_SERIALIZER, mCall.allocateRequestId());
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendHoldCallIQ() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendHoldCallIQ");
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final BinaryPacketIQ iq = new BinaryPacketIQ(IQ_HOLD_CALL_SERIALIZER, mCall.allocateRequestId());
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendResumeCallIQ() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendResumeCallIQ");
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final BinaryPacketIQ iq = new BinaryPacketIQ(IQ_RESUME_CALL_SERIALIZER, mCall.allocateRequestId());
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendKeyCheckInitiateIQ(@NonNull Locale language) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendKeyCheckInitiateIQ: language=" + language);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final KeyCheckInitiateIQ iq = new KeyCheckInitiateIQ(IQ_KEY_CHECK_INITIATE_SERIALIZER, mCall.allocateRequestId(), language);
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendOnKeyCheckInitiateIQ(@NonNull ErrorCode errorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendOnKeyCheckInitiateIQ: errorCode=" + errorCode);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final OnKeyCheckInitiateIQ iq = new OnKeyCheckInitiateIQ(IQ_ON_KEY_CHECK_INITIATE_SERIALIZER, mCall.allocateRequestId(), errorCode);
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendWordCheckResultIQ(@NonNull WordCheckResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendWordCheckResult: result=" + result);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final WordCheckIQ iq = new WordCheckIQ(IQ_WORD_CHECK_SERIALIZER, mCall.allocateRequestId(), result);
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendTerminateKeyCheckIQ(boolean result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendTerminateKeyCheckIQ: result=" + result);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final BinaryPacketIQ iq = new TerminateKeyCheckIQ(IQ_TERMINATE_KEY_CHECK_SERIALIZER, mCall.allocateRequestId(), result);
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    public void sendTwincodeUriIQ(@NonNull String uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendTwincodeUriIQ: uri=" + uri);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final BinaryPacketIQ iq = new TwincodeUriIQ(IQ_TWINCODE_URI_SERIALIZER, mCall.allocateRequestId(), uri);
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    void sendCameraControl(@NonNull CameraControlIQ.Mode control, int camera, int scale) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendCameraControl: control=" + control + " camera=" + camera + " scale=" + scale);
        }

        // If this is disabled on the relation (or not supported), don't send the camera control IQ.
        if (mZoomable == Zoomable.NEVER) {
            return;
        }
        final CameraControlIQ iq = new CameraControlIQ(IQ_CAMERA_CONTROL_SERIALIZER, mCall.allocateRequestId(), control, camera, scale);
        try {
            sendMessage(iq, StatType.IQ_SET_PUSH_TRANSIENT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    void sendCameraGrant() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendCameraGrant");
        }

        mRemoteControlGranted = true;
        int activeCamera = mCall.isFrontCamera() ? 1 : 2;
        sendCameraResponse(ErrorCode.SUCCESS, 0x03, activeCamera, 0, 100000);
    }

    /**
     * Stop controlling the peer camera.
     */
    void sendCameraStop() {

        if (mRemoteControlGranted) {
            mRemoteControlGranted = false;
            sendCameraResponse(ErrorCode.SUCCESS, 0, 0, 0, 0);
            mMainParticipant.setRemoteControl(0, 0);
            mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_CAMERA_CONTROL_DONE);
        } else {
            sendCameraControl(CameraControlIQ.Mode.STOP, 0, 0);
        }
    }

    void sendCameraResponse(@NonNull ErrorCode errorCode, long cameraBitmap, int activeCamera, long minScale, long maxScale) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendCameraResponse: errorCode=" + errorCode + " cameraBitmap=" + cameraBitmap
                    + " activeCamera=" + activeCamera + " minScale=" + minScale + " maxScale=" + maxScale);
        }

        // Even if the relation disabled camera control we must answer.
        final CameraResponseIQ responseIq = new CameraResponseIQ(IQ_CAMERA_RESPONSE_SERIALIZER, mCall.allocateRequestId(), errorCode,
                cameraBitmap, activeCamera, minScale, maxScale);
        try {
            sendMessage(responseIq, StatType.IQ_SET_PUSH_FILE);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    private void onPrepareTransferIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPrepareTransferIq: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }
        mCall.onPrepareTransfer(this);

        final BinaryPacketIQ ack = new BinaryPacketIQ(IQ_ON_PREPARE_TRANSFER_SERIALIZER, iq);
        try {
            sendMessage(ack, StatType.IQ_SET_PUSH_OBJECT);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Exception", exception);
        }
    }

    private void onOnPrepareTransferIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOnPrepareTransferIq: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }
        mCall.onOnPrepareTransfer(mPeerConnectionId);
    }

    private void onHoldCallIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onHoldCallIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null || mStatus == CallStatus.PEER_ON_HOLD) {
            return;
        }

        mStatus = CallStatus.PEER_ON_HOLD;

        mCall.onPeerHoldCall(mPeerConnectionId);
    }

    private void onResumeCallIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onResumeCallIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null || mStatus != CallStatus.PEER_ON_HOLD) {
            return;
        }

        mStatus = isVideo() ? CallStatus.IN_VIDEO_CALL : CallStatus.IN_CALL;

        mCall.onPeerResumeCall(mPeerConnectionId);
    }

    private void onKeyCheckInitiateIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onKeyCheckInitiateIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        KeyCheckInitiateIQ keyCheckInitiateIQ = (KeyCheckInitiateIQ) iq;

        mCall.onKeyCheckInitiate(mPeerConnectionId, keyCheckInitiateIQ.locale);
    }

    private void onOnKeyCheckInitiateIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOnKeyCheckInitiateIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        OnKeyCheckInitiateIQ onKeyCheckInitiateIQ = (OnKeyCheckInitiateIQ) iq;

        mCall.onOnKeyCheckInitiate(mPeerConnectionId, onKeyCheckInitiateIQ.errorCode);
    }

    private void onWordCheckIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onWordCheckIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        WordCheckIQ wordCheckIQ = (WordCheckIQ) iq;

        mCall.onWordCheck(mPeerConnectionId, wordCheckIQ.result);
    }

    private void onTerminateKeyCheckIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminateKeyCheckIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        TerminateKeyCheckIQ terminateKeyCheckIQ = (TerminateKeyCheckIQ) iq;

        mCall.onTerminateKeyCheck(mPeerConnectionId, terminateKeyCheckIQ.result);
    }

    private void onTwincodeUriIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwincodeUriIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        TwincodeUriIQ twincodeUriIQ = (TwincodeUriIQ) iq;

        mCall.onTwincodeURI(mPeerConnectionId, twincodeUriIQ.uri);
    }

    private void onScreenSharingOnIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onScreenSharingOnIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        mMainParticipant.setScreenSharing(true);
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_SCREEN_SHARING_ON);
    }

    private void onScreenSharingOffIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onScreenSharingOffIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        mMainParticipant.setScreenSharing(false);
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_SCREEN_SHARING_OFF);
    }

    private void onCameraControlIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCameraControlIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final Zoomable zoomable = mCall.isZoomableByPeer();
        if (zoomable == Zoomable.NEVER || mCall.getStatus() == CallStatus.ON_HOLD) {
            sendCameraResponse(ErrorCode.NO_PERMISSION, 0, 0, 0, 0);
            return;
        }
        final CameraControlIQ controlIQ = (CameraControlIQ) iq;
        if (zoomable == Zoomable.ASK && !mRemoteControlGranted && controlIQ.control != CameraControlIQ.Mode.STOP) {
            mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_ASK_CAMERA_CONTROL);
            return;
        }

        switch (controlIQ.control) {
            case CHECK:
                // We are either in Zoomable.ALLOW or mRemoteControlGranted is set: remote camera control is granted.
                sendCameraGrant();
                break;
            case ON:
            case OFF:
                // Send a camera ON/OFF intent.
                try {
                    final Context context = mCall.getContext();
                    final Intent intent = new Intent(context, CallService.class);
                    intent.setAction(CallService.ACTION_CAMERA_MUTE);
                    intent.putExtra(CallService.PARAM_CAMERA_MUTE, controlIQ.control == CameraControlIQ.Mode.OFF);
                    context.startService(intent);
                } catch (Exception exception) {

                }
                return;

            case SELECT:
                // Switch camera can be made directly: the UI will update as a result of a WebRTC callback.
                final int activeCamera = mCall.isFrontCamera() ? 1 : 2;
                if (controlIQ.camera != activeCamera) {
                    mPeerConnectionService.switchCamera(controlIQ.camera == 1, this::onCameraSwitch);
                }
                return;

            case ZOOM:
                int progress = controlIQ.scale;
                mPeerConnectionService.setZoom(progress);
                return;

            case STOP:
                mRemoteControlGranted = false;
                mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_CAMERA_CONTROL_DONE);
                sendCameraResponse(ErrorCode.SUCCESS, 0, 0, 0, 0);
        }
    }

    private void onCameraSwitch(@NonNull ErrorCode errorCode, @Nullable Boolean isFront) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCameraSwitch: errorCode=" + errorCode + " isFront=" + isFront);
        }

        if (errorCode != ErrorCode.SUCCESS || !mRemoteControlGranted || isFront == null || mCall.isTerminated()) {
            return;
        }

        mCall.setCameraType(isFront ? CameraType.FRONT : CameraType.BACK);

        int newActiveCamera = isFront ? 1 : 2;
        sendCameraResponse(ErrorCode.SUCCESS, 0x03, newActiveCamera, 0, 100000);
    }

    private void onCameraResponseIQ(@NonNull BinaryPacketIQ iq) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCameraResponseIQ: iq=" + iq);
        }

        if (mPeerConnectionId == null) {
            return;
        }

        final CameraResponseIQ responseIQ = (CameraResponseIQ) iq;
        if (responseIQ.errorCode != ErrorCode.SUCCESS) {
            mMainParticipant.setRemoteControl(0, 0);
            mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_CAMERA_CONTROL_DENIED);
            return;
        }

        if (responseIQ.cameraBitmap == 0) {
            mMainParticipant.setRemoteControl(0, 0);
            mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_CAMERA_CONTROL_DONE);
            return;
        }
        mMainParticipant.setRemoteControl((int)responseIQ.cameraBitmap, responseIQ.activeCamera);
        mCall.onEventParticipant(mMainParticipant, CallParticipantEvent.EVENT_CAMERA_CONTROL_GRANTED);
    }

    void putOnHold() {
        if (DEBUG) {
            Log.d(LOG_TAG, "putOnHold");
        }

        if (mRemoteControlGranted) {
            sendCameraStop();
        }

        // The initSources can be made only when the incoming peer connection is created.
        if (mPeerConnectionId != null && isDoneOperation(ConnectionOperation.CREATED_PEER_CONNECTION)) {
            mPeerConnectionService.initSources(mPeerConnectionId, false, false);
        }
    }

    void resume() {
        if (DEBUG) {
            Log.d(LOG_TAG, "resume");
        }

        initSources();
    }
}
