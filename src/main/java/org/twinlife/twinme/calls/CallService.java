/*
 *  Copyright (c) 2019-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.location.Location;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.BuildConfig;
import org.twinlife.twinlife.ConnectionStatus;
import org.twinlife.twinlife.AssertPoint;
import org.twinlife.twinlife.ConversationService;
import org.twinlife.twinlife.ConversationService.DescriptorId;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinlife.Offer;
import org.twinlife.twinlife.OfferToReceive;
import org.twinlife.twinlife.PeerCallService;
import org.twinlife.twinlife.PeerConnectionService;
import org.twinlife.twinlife.PushNotificationContent;
import org.twinlife.twinlife.PushNotificationOperation;
import org.twinlife.twinlife.PushNotificationPriority;
import org.twinlife.twinlife.TerminateReason;
import org.twinlife.twinlife.PeerConnectionService.ConnectionState;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.util.Utils;
import org.twinlife.twinlife.util.Version;
import org.twinlife.twinme.FeatureUtils;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.audio.AudioDevice;
import org.twinlife.twinme.audio.AudioListener;
import org.twinlife.twinme.audio.TwinmeAudioManager;
import org.twinlife.twinme.calls.keycheck.KeyCheckSessionHandler;
import org.twinlife.twinme.calls.keycheck.WordCheckChallenge;
import org.twinlife.twinme.calls.keycheck.WordCheckResult;
import org.twinlife.twinme.calls.telecom.TelecomConnectionService;
import org.twinlife.twinme.calls.telecom.TelecomUtils;
import org.twinlife.twinme.models.CallReceiver;
import org.twinlife.twinme.models.Capabilities;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Group;
import org.twinlife.twinme.models.GroupMember;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.models.schedule.Schedule;
import org.twinlife.twinme.NotificationCenter;
import org.twinlife.twinme.ui.Intents;
import org.twinlife.twinme.TwinmeApplicationImpl;
import org.twinlife.twinme.utils.MediaMetaData;
import org.webrtc.AudioTrack;
import org.webrtc.EglBase;
import org.webrtc.MediaStreamTrack;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver.RtpTransceiverDirection;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoTrack;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.twinlife.twinme.calls.CallState.TransferDirection.*;
import static org.twinlife.twinme.NotificationCenter.CALL_SERVICE_INCALL_NOTIFICATION_ID;
import static org.twinlife.twinme.NotificationCenter.CALL_SERVICE_INCOMING_NOTIFICATION_ID;
import static org.twinlife.twinme.NotificationCenter.CALL_SERVICE_NOTIFICATION_ID;

/**
 * Audio or video call foreground service.
 *
 * The service manages a P2P audio/video call and it runs as a foreground service.  It is associated with a notification
 * that allows to control the audio/video call.  Some important notes:
 *
 * Calls:
 * - The CallService manages two audio/video calls: an active audio/video call represented by mActiveCall and a possible
 * second audio/video call which is on-hold (is it worth to manage several on-hold calls? probably not).
 * - When an incoming call is received which we are already in a call, a mHoldCall is created (it is not accepted).
 * - The holding call can be accepted in which case it becomes active and the active call is put on-hold.
 * - If the active call terminates, the mHoldCall becomes active.
 *
 * Connections:
 * - The CallService maintains a list of active peer connections for the 1-1 call, for 1-N group calls and for 1-1 call
 * with the ability to put a call on hold.  Each connection is represented by a CallConnection.
 * - To prepare to the future, the CallParticipant represents a user that participate in a call. It is separated
 * from the CallConnection to allow different architectures (ex: a same P2P connection that provides different tracks
 * one for each participant).
 *
 * Videos:
 * - The video EGL context is created only when the video call is accepted for an incoming call, or when we start the outgoing video call.
 * This is done by 'setupEGLContact()' which is synchronized and can be called from any thread.
 * - Each participant must be configured for video by calling `setupVideo` from the main UI thread only.
 * - The video SurfaceView(s) are allocated by the CallService and the VideoCallActivity retrieves them through static methods.
 * They cannot be passed to Intent.  SurfaceView(s) are associated with CallParticipant.
 *
 * Notifications:
 * - When an audio/video call wakes up the application, the Firebase message starts the CallService but we don't know the
 * contact yet.  We MUST create a notification and associate it with the service.  The CallService will trigger the Twinlife
 * service initialization through the JobService.  This is handled by onActionIncomingNotification().
 * - The audio/video incoming call can also be started without Firebase.  In that case, onActionIncomingCall() is invoked and
 * we know the contact.  We also create a notification and associate it with the service.  We have to be careful that
 * onActionIncomingNotifcation() will ALSO be called.
 * - We must call the Service.startForeground() several times because some call will be ignored by Android 10 and 11 when the
 * application is in background.  Only the call made as a result of Firebase message will allow us to start the CallService
 * as a foreground service.
 * - The CallService is now started from the main thread to limit the risks of not calling the startForground()
 * within the 5 seconds constraints.
 * - For an incoming call, we use a first notification ID either CALL_SERVICE_INCOMING_AUDIO_NOTIFICATION_ID or
 * CALL_SERVICE_INCOMING_VIDEO_NOTIFICATION_ID and when the call is accepted we switch to a second notification ID
 * CALL_SERVICE_INCALL_NOTIFICATION_ID.  This is necessary on Android 12 because the notification content is not updated.
 * - It is critical to always call startNotification() when an onStartCommand() is processed and the call must be made
 * by the current thread.  This is why it is sometimes called several times: a first time on the onActionXXX() method
 * and another time from startCall() or from getOriginator()'s lambda when we know the contact/group.
 *
 * Executors & timers:
 * - a dedicated executor thread is used to perform some possibly blocking tasks such as some media player operations
 * - the P2P connection timer is specific to each CallConnection so that they are independent from each other
 * - the CallService has a shutdown timer that is fired at the end to terminate the CallService 3s after the last call terminate
 *   (see FINISH_TIMEOUT)
 */
public class CallService extends Service implements PeerConnectionService.PeerConnectionObserver, AudioListener {
    private static final String LOG_TAG = "CallService";
    private static final boolean DEBUG = false;

    /**
     * Actions supported by the service.
     */
    public static final String ACTION_AUDIO_MUTE = "audioMute";
    public static final String ACTION_ACCEPT_CALL = "acceptCall";
    public static final String ACTION_TERMINATE_CALL = "terminateCall";
    public static final String ACTION_INCOMING_CALL = "incomingCall";
    public static final String ACTION_OUTGOING_CALL = "outgoingCall";
    public static final String ACTION_SPEAKER_MODE = "speakerMode";
    public static final String ACTION_CHECK_STATE = "checkState";
    public static final String ACTION_SWITCH_CAMERA = "switchCamera";
    public static final String ACTION_CAMERA_MUTE = "cameraMute";
    public static final String ACTION_SWITCH_CALL_MODE = "switchCallMode";
    public static final String ACTION_SWAP_CALLS = "swapCall";
    public static final String ACTION_HOLD_CALL = "holdCall";
    public static final String ACTION_RESUME_CALL = "resumeCall";
    public static final String ACTION_ACTIVITY_RESUMED = "activityResumed";

    public static final String ACTION_MERGE_CALLS = "mergeCalls";
    public static final String ACTION_QUALITY_CALL = "qualityCall";
    public static final String ACTION_START_STREAMING = "startStreaming";
    public static final String ACTION_STOP_STREAMING = "stopStreaming";
    public static final String ACTION_ACCEPT_TRANSFER = "acceptTransfer";
    public static final String ACTION_START_KEY_CHECK = "startKeyCheck";
    public static final String ACTION_WORD_CHECK_RESULT = "wordCheckResult";
    public static final String ACTION_STOP_KEY_CHECK = "stopKeyCheck";
    public static final String ACTION_AUDIO_STATE_CHANGED = "audioStateChanged";

    /**
     * Action parameters.
     */
    public static final String PARAM_AUDIO_MUTE = "audioMute";
    public static final String PARAM_AUDIO_SPEAKER = "audioSpeaker";
    public static final String PARAM_TERMINATE_REASON = "terminateReason";
    public static final String PARAM_CONTACT_ID = "contactId";
    public static final String PARAM_GROUP_ID = "groupId";
    public static final String PARAM_CALL_ADD_PARTICIPANT = "addParticipant";
    public static final String PARAM_CALL_ID = "callId";
    public static final String PARAM_PEER_CONNECTION_ID = "peerConnectionId";
    public static final String PARAM_CAMERA_MUTE = "cameraMute";
    public static final String PARAM_CALL_MODE = "callMode";
    public static final String PARAM_GROUP_CALL = "groupCall";
    public static final String PARAM_TRANSFER = "transfer";
    public static final String PARAM_CALL_QUALITY = "callQuality";
    public static final String PARAM_STREAMING_PATH = "streamingPath";
    public static final String PARAM_STREAMING_INFO = "streamingInfo";
    public static final String PARAM_KEY_CHECK_LANGUAGE = "language";
    public static final String PARAM_WORD_CHECK_INDEX = "wordCheckIndex";
    public static final String PARAM_WORD_CHECK_RESULT = "wordCheckResult";

    /**
     * Event message types.
     */
    public static final String MESSAGE_CREATE_INCOMING_CALL = "createIncomingCall";
    public static final String MESSAGE_CREATE_OUTGOING_CALL = "createOutgoingCall";
    public static final String MESSAGE_ACCEPTED_CALL = "acceptedCall";
    public static final String MESSAGE_CONNECTION_STATE = "connectionState";
    public static final String MESSAGE_TERMINATE_CALL = "terminateCall";
    public static final String MESSAGE_VIDEO_UPDATE = "videoUpdate";
    public static final String MESSAGE_ERROR = "error";
    public static final String MESSAGE_STATE = "state";
    public static final String MESSAGE_AUDIO_MUTE_UPDATE = "audioMuteUpdate";
    public static final String MESSAGE_AUDIO_SINK_UPDATE = "sinkUpdate";
    public static final String MESSAGE_CAMERA_SWITCH = "cameraSwitch";
    public static final String MESSAGE_CAMERA_MUTE_UPDATE = "cameraUpdate";
    public static final String MESSAGE_TRANSFER_REQUEST = "transferRequest";
    public static final String MESSAGE_CALL_ON_HOLD = "callOnHold";
    public static final String MESSAGE_CALL_RESUMED = "callResumed";
    public static final String MESSAGE_CALLS_MERGED = "callMerged";
    public static final String MESSAGE_USER_LOCATION_UPDATE = "userLocationUpdate";

    /**
     * Event content attributes.
     */
    public static final String CALL_SERVICE_EVENT = "event";
    public static final String CALL_SERVICE_STATE = "callState";
    public static final String CALL_SERVICE_TERMINATE_REASON = "terminateReason";
    public static final String CALL_SERVICE_CONNECTION_STATE = "connectionState";
    public static final String CALL_SERVICE_CONNECTION_START_TIME = "connectionStartTime";
    public static final String CALL_SERVICE_PEER_CONNECTION_ID = "peerConnectionId";
    public static final String CALL_SELECTED_AUDIO_SINK = "audioSink";
    public static final String CALL_AUDIO_DEVICE_NAME = "audioDeviceName";
    public static final String CALL_MUTE_STATE = "muteState";
    public static final String CALL_CAMERA_MUTE_STATE = "cameraMuteState";
    public static final String CALL_HAS_CAMERA = "hasCamera";
    public static final String CALL_IS_DOUBLE_CALL = "isDoubleCall";
    public static final String CALL_ERROR_STATUS = "errorStatus";
    public static final String CALL_CONTACT_ID = "contactId";
    public static final String CALL_GROUP_ID = "groupId";
    public static final String CALL_ID = "callId";
    public static final String CALL_IS_HOLD_CALL = "isHoldCall";
    public static final String CALL_USER_LOCATION_LATITUDE = "latitude";
    public static final String CALL_USER_LOCATION_LONGITUDE = "longitude";

    private static final int CALL_INCOMING_TIMEOUT = 30; // 30s
    private static final int CALL_OUTGOING_TIMEOUT = CALL_INCOMING_TIMEOUT + 15; // Give 15s more to deliver the push and wakeup the device.
    private static final int CONNECT_TIMEOUT = 15; // After accepting a call, delay before we get the connection.
    private static final int FINISH_TIMEOUT = 3;
    private static final int ANDROID_STARTUP_SERVICE_MAIN_THREAD_HANGING_HACK_DELAY = 10000; // 10s in ms

    private class TwinmeContextObserver extends TwinmeContext.DefaultObserver {

        @Override
        public void onTwinlifeReady() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeReady");
            }

            CallService.this.onTwinlifeReady();
        }

        @Override
        public void onTwinlifeOnline() {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onTwinlifeOnline");
            }

            CallService.this.onTwinlifeOnline();
        }

        @Override
        public void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onDisconnect "+ connectionStatus);
            }

            CallService.this.onConnectionStatusChange(connectionStatus);
        }

        @Override
        public void onUpdateContact(long requestId, @NonNull final Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onUpdateContact: requestId=" + requestId + " contact=" + contact);
            }

            CallService.this.onUpdateContact(contact);
        }

        @Override
        public void onError(long requestId, ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            final ConnectionOperation request;
            synchronized (mConnectionRequestIds) {
                request = mConnectionRequestIds.remove(requestId);
            }

            if (request != null) {
                CallService.this.onError(request.callConnection, request.operation, errorCode, errorParameter);
            }
        }
    }

    private class ConversationServiceObserver extends ConversationService.DefaultServiceObserver {

        @Override
        public void onPushDescriptor(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onPushDescriptor: requestId=" + requestId + " conversation=" + conversation.getId()
                        + " descriptor=" + descriptor.getDescriptorId());
            }

            final CallOperation request;
            synchronized (mCallRequestIds) {
                request = mCallRequestIds.remove(requestId);
            }

            if (request != null && request.operation == CallOperation.START_CALL) {
                request.call.startCall(descriptor.getDescriptorId());
                request.call.checkOperation(CallOperation.START_CALL_DONE);

                final List<CallConnection> connections = request.call.getConnections();
                for (CallConnection connection : connections) {
                    onOperation(connection);
                }
            }
        }

        @Override
        public void onPopDescriptor(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onPopDescriptor: requestId=" + requestId + " conversation=" + conversation.getId()
                        + " descriptor=" + descriptor.getDescriptorId());
            }

            final CallOperation request;
            synchronized (mCallRequestIds) {
                request = mCallRequestIds.remove(requestId);
            }

            if (request != null && request.operation == CallOperation.START_CALL) {
                request.call.startCall(descriptor.getDescriptorId());
                request.call.checkOperation(CallOperation.START_CALL_DONE);

                final List<CallConnection> connections = request.call.getConnections();
                for (CallConnection connection : connections) {
                    onOperation(connection);
                }
            }
        }

        @Override
        public void onUpdateDescriptor(long requestId, @NonNull ConversationService.Conversation conversation, @NonNull ConversationService.Descriptor descriptor, ConversationService.UpdateType updateType) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onUpdateDescriptor: requestId=" + requestId + " conversation=" + conversation.getId()
                        + " descriptor=" + descriptor.getDescriptorId());
            }

            final CallOperation request;
            synchronized (mCallRequestIds) {
                request = mCallRequestIds.remove(requestId);
            }

            if (request != null) {

                if (request.operation == CallOperation.ACCEPTED_CALL) {
                    request.call.checkOperation(CallOperation.ACCEPTED_CALL_DONE);
                } else if (request.operation == CallOperation.TERMINATE_CALL) {
                    request.call.checkOperation(CallOperation.TERMINATE_CALL_DONE);
                }
            }
        }

    }

    private class PeerCallServiceObserver implements PeerCallService.ServiceObserver {

        @Override
        public void onCreateCallRoom(long requestId, @NonNull UUID callRoomId, @NonNull String memberId, int maxMemberCount) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onCreateCallRoom: requestId=" + requestId + " callRoomId=" + callRoomId);
            }

            final ConnectionOperation request;
            synchronized (mConnectionRequestIds) {
                request = mConnectionRequestIds.remove(requestId);
            }

            if (request != null) {
                CallService.this.onCreateCallRoom(request.call, callRoomId, memberId, maxMemberCount);
            }
        }

        @Override
        public void onInviteCallRoom(@NonNull UUID callRoomId, @NonNull UUID twincodeId, @Nullable UUID p2pSession, int maxCount) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onInviteCallRoom: callRoomId=" + callRoomId + " twincodeId=" + twincodeId);
            }

            CallService.this.onInviteCallRoom(callRoomId, twincodeId, p2pSession, maxCount);
        }

        @Override
        public void onJoinCallRoom(long requestId, @NonNull UUID callRoomId, @NonNull String memberId,
                                   @NonNull List<PeerCallService.MemberInfo> members) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onJoinCallRoom: requestId=" + requestId + " callRoomId=" + callRoomId
                        + " memberId=" + memberId);
            }

            final CallOperation request;
            synchronized (mCallRequestIds) {
                request = mCallRequestIds.remove(requestId);
            }

            if (request != null) {
                CallService.this.onJoinCallRoom(request.call, callRoomId, memberId, members);
            }
        }

        @Override
        public void onMemberJoinCallRoom(@NonNull UUID callRoomId, @NonNull String memberId, @Nullable UUID p2pSession,
                                         @NonNull PeerCallService.MemberStatus status) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onMemberJoinCallRoom: callRoomId=" + callRoomId + " memberId=" + memberId);
            }

            CallService.this.onMemberJoinCallRoom(callRoomId, memberId, p2pSession, status);
        }

        @Override
        public void onTransferDone() {
            CallService.this.onTransferDone();
        }
    }

    private class PeerConnectionServiceObserver extends PeerConnectionService.DefaultServiceObserver {

        @Override
        public void onIncomingPeerConnection(@NonNull UUID peerConnectionId, @NonNull String peerId,
                                             @NonNull Offer offer) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onIncomingPeerConnection: peerConnectionId=" + peerConnectionId
                        + " peerId=" + peerId + " offer=" + offer);
            }

            CallConnection callConnection = findPeerConnection(peerConnectionId);
            if (callConnection == null) {
                final CallState call = getState();
                if (call == null || mPeerConnectionService == null) {
                    return;
                }
                int pos = peerId.indexOf('@');
                if (pos < 0) {
                    return;
                }

                final String domain = peerId.substring(pos + 1);
                pos = domain.indexOf('.');
                if (pos < 0 || !domain.startsWith(".callroom.", pos)) {
                    return;
                }

                final UUID callRoomId = Utils.UUIDFromString(domain.substring(0, pos));
                if (callRoomId == null) {
                    return;
                }

                if (!callRoomId.equals(call.getCallRoomId())) {
                    return;
                }

                CallStatus status = offer.video ? CallStatus.ACCEPTED_INCOMING_VIDEO_CALL : CallStatus.ACCEPTED_INCOMING_CALL;
                callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                        call, peerConnectionId, status, peerId);
                callConnection.checkOperation(ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION);

                callConnection.setPeerVersion(offer.version);
                callConnection.setInvited(true);

                synchronized (this) {
                    mPeers.put(peerConnectionId, callConnection);
                }

                call.addPeerConnection(callConnection);

                if (callConnection.getMainParticipant().getTransferredFromParticipantId() == null) {
                    // We have no info on the participant yet so we want to display no name
                    // and the default avatar until we get the ParticipantInfoIQ.
                    // But we don't want to do this for transfer targets,
                    // as their info will be copied from the transferred participant (see CallParticipant.transfer()).
                    callConnection.getMainParticipant().setInformation(null, null, mTwinmeContext.getDefaultAvatar(), null);
                }
                // Setup the video renderer on the call participant from the main thread.
                if (offer.video) {
                    final CallParticipant participant = callConnection.getMainParticipant();
                    call.getHandler().post(() -> setupVideo(participant));
                }

            }
            CallService.this.onOperation(callConnection);
        }

        @Override
        public void onCreateLocalVideoTrack(@NonNull final VideoTrack videoTrack) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onCreateLocalVideoTrack: videoTrack=" + videoTrack);
            }

            CallService.this.onCreateLocalVideoTrack(videoTrack);
        }

        @Override
        public void onDeviceRinging(UUID peerConnectionId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onDeviceRinging");
            }

            CallConnection callConnection = findPeerConnection(peerConnectionId);

            if (callConnection != null) {
                CallService.this.onDeviceRinging(callConnection);
            }
        }

        @Override
        public void onError(long requestId, final ErrorCode errorCode, final String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            final ConnectionOperation request;
            synchronized (mConnectionRequestIds) {
                request = mConnectionRequestIds.remove(requestId);
            }

            if (request != null) {
                CallService.this.onError(request.callConnection, request.operation, errorCode, errorParameter);
            }
        }
    }

    static private volatile CallService sCurrent = null;
    static private volatile StartNotification sNotificationInfo = null;
    static private final Object sLock = new Object();

    private TwinmeContext mTwinmeContext;
    private TwinmeApplicationImpl mTwinmeApplication;
    private volatile boolean mConnected;
    private volatile int mStartId;

    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> mShutdownTimer;
    private JobService.ProcessingLock mProcessingLock;
    private JobService.NetworkLock mNetworkLock;

    @SuppressLint("UseSparseArrays")
    private final Map<Long, ConnectionOperation> mConnectionRequestIds = new HashMap<>();
    private final Map<Long, CallOperation> mCallRequestIds = new HashMap<>();
    private boolean mAudioMute = false;
    private boolean mIsCameraMute = false;

    private final TwinmeContextObserver mTwinmeContextObserver = new TwinmeContextObserver();
    private final PeerConnectionServiceObserver mPeerConnectionServiceObserver = new PeerConnectionServiceObserver();
    private final PeerCallServiceObserver mPeerCallServiceObserver = new PeerCallServiceObserver();
    private final ConversationServiceObserver mConversationServiceObserver = new ConversationServiceObserver();
    private final Map<UUID, CallConnection> mPeers = new HashMap<>();
    private final Map<UUID, CallState> mCallsContacts = new HashMap<>();
    private UUID mPeerConnectionIdTerminated;
    @Nullable
    private volatile CallState mActiveCall;
    @Nullable
    private volatile CallState mHoldCall;

    @Nullable
    private PeerConnectionService mPeerConnectionService;
    @Nullable
    private PeerCallService mPeerCallService;
    @Nullable
    private TwinmeAudioManager mAudioManagerDevices;
    private NotificationCenter mNotificationCenter;
    private final Map<UUID, Notification> mCallNotifications = new HashMap<>();
    @Nullable
    private Notification mServiceNotification;
    private int mNotificationId = CALL_SERVICE_NOTIFICATION_ID;
    private int mParticipantCounter = 0;

    @Nullable
    private KeyCheckSessionHandler mKeyCheckSessionHandler;
    private final LocationManager mLocationManager = new LocationManager();

    /**
     * Set to true when CallActivity has been started and displayed (i.e. onResume() has been called at least once).
     */

    private final AtomicBoolean mActivityStarted = new AtomicBoolean(false);

    @Nullable
    private TelecomManager mTelecomManager;

    @Nullable
    private PhoneAccountHandle mPhoneAccountHandle;

    /*
     * Track the last service types used to call startForeground(), to decide whether we need to call it again.
     */
    private int mPreviousServiceTypes = 0;

    /*
     * Track the status when we last called startForeground(), to decide whether we need to call it again.
     */
    @Nullable
    private CallStatus mPreviousCallStatus = null;

    @Nullable
    private static volatile WeakReference<CallParticipantObserver> sParticipantObserver;

    @Nullable
    private static volatile WeakReference<Context> sUiContext;

    @NonNull
    private static final Handler uiThreadHandler = new Handler(Looper.getMainLooper());

    //
    // Static global and public operations.
    //

    /**
     * Check if the CallService is running.
     *
     * @return true if the CallService is running.
     */
    public static boolean isRunning() {

        return sCurrent != null;
    }

    public static void setObserver(@Nullable CallParticipantObserver observer) {
        sUiContext = null;
        if (observer == null) {
            sParticipantObserver = null;
        } else {
            sParticipantObserver = new WeakReference<>(observer);
            if (observer instanceof Activity) {
                sUiContext = new WeakReference<>((Activity) observer);
            } else if (BuildConfig.ENABLE_CHECKS){
                throw new IllegalStateException("observer should be an Activity");
            }
        }
    }

    @Nullable
    CallParticipantObserver getParticipantObserver() {

        final WeakReference<CallParticipantObserver> observer = sParticipantObserver;
        if (observer == null) {
            return null;
        } else {
            return observer.get();
        }
    }

    @NonNull
    Context getUiContext() {
        Context res = null;
        synchronized (this) {
            final WeakReference<Context> uiContext = sUiContext;
            if (uiContext != null) {
                res = uiContext.get();
            }
        }

        if (res != null) {
            return res;
        }

        if (BuildConfig.ENABLE_CHECKS) {
            throw new IllegalStateException("sUiContext is null");
        }
        // We don't have a UI-aware context so let's try with this service
        return this;
    }

    /**
     * Get the current state of the CallService.
     *
     * @return the current state of the CallService or null.
     */
    @Nullable
    public static CallStatus getCurrentMode() {

        final CallService service = sCurrent;
        if (service == null) {
            return null;
        } else {
            return service.getMode();
        }
    }

    /**
     * Start the CallService when we know the contact.
     * <p>
     * The intent is used to start the CallService for an incoming audio/video call.
     *
     * @param context          the application context.
     * @param contact          the contact
     * @param avatar           the contact's avatar (thumbnail)
     * @param peerConnectionId the P2P session id.
     * @param offer            the incoming offer.
     */
    public static void startService(@NonNull Context context, @NonNull Originator contact,
                                    @Nullable Bitmap avatar, @NonNull UUID peerConnectionId, @NonNull Offer offer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startService context=" + context + " contact=" + contact
                    + " peerConnectionId=" + peerConnectionId + " offer=" + offer);
        }

        final CallStatus callStatus;
        if (offer.videoBell || offer.video) {
            callStatus = CallStatus.INCOMING_VIDEO_CALL;
        } else {
            callStatus = CallStatus.INCOMING_CALL;
        }

        boolean callInProgress = false;
        synchronized (sLock) {
            CallService service = sCurrent;
            if (service != null) {
                if (service.isPeerConnection(peerConnectionId)) {
                    return;
                }

                CallState activeCall = service.getActiveCall();

                if (activeCall != null && CallStatus.isActive(activeCall.getStatus())) {
                    callInProgress = true;
                }
            }

            sNotificationInfo = new StartNotification(callStatus, peerConnectionId, contact, avatar);
        }

        Intent intent = new Intent(context, CallService.class);
        intent.putExtra(PARAM_CALL_MODE, callStatus);
        intent.putExtra(PARAM_PEER_CONNECTION_ID, peerConnectionId);
        intent.putExtra(PARAM_CONTACT_ID, contact.getId());
        intent.putExtra(PARAM_GROUP_CALL, offer.group);
        intent.putExtra(PARAM_TRANSFER, offer.transfer);
        intent.setAction(ACTION_INCOMING_CALL);

        if (contact.getType() == Originator.Type.GROUP_MEMBER) {
            intent.putExtra(PARAM_GROUP_ID, ((GroupMember) contact).getGroup().getId());
        }

        if (!callInProgress && FeatureUtils.isTelecomSupported(context) && TelecomUtils.addIncomingTelecomCall(context, peerConnectionId, contact, offer.video)) {
            // Keep the intent to start CallService once Telecom gives us the go-ahead (see TelecomConnectionService.onCreateIncomingConnection()).
            synchronized (sLock) {
                sNotificationInfo.startCallServiceIntent = intent;
            }
        } else {
            startService(context, intent);
        }
    }

    /**
     * Used by {@link TelecomConnectionService} to start CallService once the call has been
     * successfully added.
     * @param context needed to call startService
     */
    public static void startService(@NonNull Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startService: context=" + context);
        }

        Intent intent;

        synchronized (sLock) {
            intent = sNotificationInfo.startCallServiceIntent;
        }

        if (intent == null) {
            Log.e(LOG_TAG, "No intent found in sNotificationInfo, can't start CallService");
            return;
        }

        startService(context, intent);
    }

    /**
     * Start a service with the given intent.
     * <p>
     * With Android 9, if the user forbids the application to start a foreground service when it is in background,
     * starting the PeerService will proceed but we are not aware of the problem: the foreground service is
     * simply ignored and has no effect on keeping the application running.
     *
     * @param context the application context.
     * @param intent  the service intent.
     */
    private static void startService(@NonNull Context context, @NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startService context=" + context + " intent=" + intent);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final long start = System.currentTimeMillis();
            uiThreadHandler.post(() -> {
                long delay = System.currentTimeMillis() - start;

                if (delay < ANDROID_STARTUP_SERVICE_MAIN_THREAD_HANGING_HACK_DELAY) {

                    // Main thread does not seem stuck: we may have more chance to honor the Service.startForeground() call.
                    try {
                        context.startForegroundService(intent);
                    } catch (RuntimeException exception) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Cannot start foreground service: ", exception);
                        }
                    }
                } else {
                    Log.e(LOG_TAG, "Oops too big delay to start the foreground service delay was " + delay);
                }
            });
        } else {
            try {
                context.startService(intent);
            } catch (RuntimeException exception) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Cannot start callService: ", exception);
                }
            }
        }
    }

    /**
     * Get the current call state which describes and contains the current call information.
     * There could be no, one, or several active P2P connections.
     *
     * @return the current call state or null.
     */
    @Nullable
    public static CallState getState() {

        final CallService current = sCurrent;
        if (current != null) {
            return current.mActiveCall;
        } else {
            return null;
        }
    }

    @Nullable
    public static CallState getHoldState() {

        final CallService current = sCurrent;
        if (current != null) {
            return current.mHoldCall;
        } else {
            return null;
        }
    }

    @Nullable
    public static CallAudioManager getAudioManager() {

        final CallService current = sCurrent;

        if (current == null) {
            return null;
        }

        CallState call = current.getActiveCall();
        if (call != null && call.isTelecomSupported()) {
            return TelecomConnectionService.getInstance();
        } else {
            return current.mAudioManagerDevices;
        }

    }

    public static boolean isDoubleCall() {
        return getHoldState() != null;
    }

    public static boolean isKeyCheckRunning() {
        final CallService current = sCurrent;
        if (current != null) {
            return current.mKeyCheckSessionHandler != null;
        }

        return false;
    }

    /**
     * Get the word we're currently checking.
     * <p>
     * After starting the session, the first word will be returned. Once both sides have checked the word,
     * the second word will be returned, and so on.
     *
     * @return the current word, or null if there is no key check session
     */
    @Nullable
    public static WordCheckChallenge getKeyCheckCurrentWord() {
        final CallService current = sCurrent;
        if (current != null) {
            final KeyCheckSessionHandler handler = current.mKeyCheckSessionHandler;
            if (handler != null) {
                return handler.getCurrentWord();
            }
        }

        return null;
    }

    /**
     * Get the word marked as invalid by the peer (if any).
     *
     * @return the word marked as invalid by the peer, or null if there is no key check session, or if the peer has not marked a word as invalid.
     */
    @Nullable
    public static WordCheckChallenge getKeyCheckPeerError() {
        final CallService current = sCurrent;
        if (current != null) {
            final KeyCheckSessionHandler handler = current.mKeyCheckSessionHandler;
            if (handler != null) {
                return handler.getPeerError();
            }
        }

        return null;
    }

    /**
     * Check the state of a key check session.
     *
     * @return null if there is no key check session, true if there is an active session and we have the results for all words for both sides, false otherwise.
     */
    @Nullable
    public static Boolean isKeyCheckDone() {
        final CallService current = sCurrent;
        if (current != null) {
            final KeyCheckSessionHandler handler = current.mKeyCheckSessionHandler;
            if (handler != null) {
                return handler.isDone();
            }
        }

        return null;
    }

    /**
     * Check the result of a key check session.
     *
     * @return null if there is no key check session or we haven't got all the results, true if all words were confirmed by both sides, false otherwise.
     */
    @Nullable
    public static Boolean isKeyCheckOK() {
        final CallService current = sCurrent;
        if (current != null) {
            final KeyCheckSessionHandler handler = current.mKeyCheckSessionHandler;
            if (handler != null) {
                return handler.isOK();
            }
        }

        return null;
    }

    private void sendGeolocation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendGeolocation");
        }

        CallState call = mActiveCall;
        Location userLocation = mLocationManager.getUserLocation();
        if (call != null && userLocation != null && !call.getStatus().isOnHold()) {
            call.sendGeolocation(userLocation.getLongitude(), userLocation.getLatitude(), userLocation.getAltitude(), mLocationManager.getMapLongitudeDelta(), mLocationManager.getMapLatitudeDelta());
        }
    }

    void sendGeolocation(@NonNull CallConnection connection) {
        CallState call = connection.getCall();
        Location userLocation = mLocationManager.getUserLocation();
        if (userLocation == null) {
            return;
        }
        connection.sendDescriptor(call.createGeolocation(userLocation.getLongitude(), userLocation.getLatitude(), userLocation.getAltitude(), mLocationManager.getMapLongitudeDelta(), mLocationManager.getMapLatitudeDelta()));
    }

    public static boolean isLocationStartShared() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isLocationStartShared");
        }

        return sCurrent != null && sCurrent.mLocationManager.isLocationShared();
    }

    public static void initShareLocation(Context context) {
        if (DEBUG) {
            Log.d(LOG_TAG, "initShareLocation");
        }

        final CallService current = sCurrent;

        current.mLocationManager.initShareLocation(context);
    }

    public static void startShareLocation(double mapLatitudeDelta, double mapLongitudeDelta) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startShareLocation");
        }

        final CallService current = sCurrent;

        current.mLocationManager.startShareLocation(mapLatitudeDelta, mapLongitudeDelta);

        if (current.mLocationManager.getUserLocation() != null) {
            current.sendGeolocation();
        }
    }

    public static void stopShareLocation(boolean destroy) {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopShareLocation");
        }

        final CallService current = sCurrent;
        if (current != null) {
            current.mLocationManager.stopShareLocation(destroy);

            CallState call = current.mActiveCall;
            if (call != null) {
                call.deleteGeolocation();
            }
        }
    }

    public static Location getCurrentLocation() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentLocation");
        }

        final CallService current = sCurrent;
        return current.mLocationManager.getUserLocation();
    }

    //
    // Service operations
    //

    @Override
    public void onCreate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreate");
        }

        sCurrent = this;

        initialize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onStartCommand intent=" + intent + " flags=" + flags + " startId=" + startId
                    + " action=" + (intent == null ? "null" : intent.getAction()));
        }

        mStartId = startId;
        if (intent == null || intent.getAction() == null || !isAlive()) {
            startNotification();

            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {

            case ACTION_INCOMING_CALL:
                onActionIncomingCall(intent);
                break;

            case ACTION_OUTGOING_CALL:
                onActionOutgoingCall(intent);
                break;

            case ACTION_ACCEPT_CALL:
                runIfCallInProgress(intent, this::onActionAcceptCall);
                break;

            case ACTION_TERMINATE_CALL:
                runIfCallInProgress(intent, this::onActionTerminateCall);
                break;

            case ACTION_AUDIO_MUTE:
                runIfCallInProgress(intent, this::onActionAudioMute);
                break;

            case ACTION_SPEAKER_MODE:
                runIfCallInProgress(intent, this::onActionSpeaker);
                break;

            case ACTION_SWITCH_CAMERA:
                runIfCallInProgress(intent, this::onActionSwitchCamera);
                break;

            case ACTION_CAMERA_MUTE:
                runIfCallInProgress(intent, this::onActionCameraMute);
                break;

            case ACTION_CHECK_STATE:
                runIfCallInProgress(intent, this::onActionCheckState);
                break;

            case ACTION_SWITCH_CALL_MODE:
                runIfCallInProgress(intent, this::onActionSwitchCallMode);
                break;

            case ACTION_SWAP_CALLS:
                runIfCallInProgress(intent, this::onActionSwitchCall);
                break;

            case ACTION_HOLD_CALL:
                runIfCallInProgress(intent, this::onActionHoldCall);
                break;

            case ACTION_RESUME_CALL:
                runIfCallInProgress(intent, this::onActionResumeCall);
                break;

            case ACTION_MERGE_CALLS:
                runIfCallInProgress(intent, this::onActionMergeCalls);
                break;

            case ACTION_QUALITY_CALL:
                runIfCallInProgress(intent, this::onActionCallQuality);
                break;

            case ACTION_START_STREAMING:
                runIfCallInProgress(intent, this::onActionStartStreaming);
                break;

            case ACTION_STOP_STREAMING:
                runIfCallInProgress(intent, this::onActionStopStreaming);
                break;

            case ACTION_ACCEPT_TRANSFER:
                runIfCallInProgress(intent, this::onActionAcceptTransfer);
                break;

            case ACTION_START_KEY_CHECK:
                runIfCallInProgress(intent, this::onActionStartKeyCheck);
                break;

            case ACTION_WORD_CHECK_RESULT:
                runIfCallInProgress(intent, this::onActionWordCheckResult);
                break;

            case ACTION_STOP_KEY_CHECK:
                runIfCallInProgress(intent, this::onActionStopKeyCheck);
                break;

            case ACTION_ACTIVITY_RESUMED:
                mActivityStarted.set(true);
                runIfCallInProgress(intent, i -> {});
                break;

            case ACTION_AUDIO_STATE_CHANGED:
                runIfCallInProgress(intent, this::onCallAudioStateChanged);
                break;
        }

        return START_NOT_STICKY;
    }

    /**
     * Checks whether we should actually act on the intent, stop the service if not.
     * @param intent the intent received by CallService.
     * @param action the action to perform with the intent.
     */
    private void runIfCallInProgress(@NonNull Intent intent, @NonNull TwinmeContext.Consumer<Intent> action) {
        CallState callState;
        StartNotification startNotification;

        synchronized (this) {
            callState = getActiveCall();
        }
        synchronized (sLock) {
            startNotification = sNotificationInfo;
        }

        if (callState == null && startNotification == null) {
            // Service was not running before we received the intent =>
            // ignore intent and cleanly stop the service.
            // (intent is most likely a leftover from the call that just ended, and wasn't
            // received by the active CallService in time)
            startNotification(true);
            finish();
            return;
        }

        action.accept(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onBind");
        }

        // We don't provide binding, so return null
        return null;
    }

    @Override
    public synchronized void onDestroy() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDestroy");
        }

        if (mPeerConnectionService != null) {
            mPeerConnectionService.removeServiceObserver(mPeerConnectionServiceObserver);
        }
        if (mPeerCallService != null) {
            mPeerCallService.removeServiceObserver(mPeerCallServiceObserver);
        }

        if (mTwinmeContext.hasTwinlife()) {
            mTwinmeContext.getConversationService().removeServiceObserver(mConversationServiceObserver);
        }
        mTwinmeContext.removeObserver(mTwinmeContextObserver);

        mTwinmeApplication.setInCallInfo(null);
        stopForeground(true);

        stopShareLocation(true);

        // Force a cancel of the notification since the service may not be associated with the notification
        // if the service was started while in background and with battery restrictions.
        mNotificationCenter.cancel(mNotificationId);

        sNotificationInfo = null;
        sCurrent = null;
        super.onDestroy();

        stopRingtone();

        CallState call = mActiveCall;
        if (call != null) {
            mActiveCall = null;
            call.release();
        }
        call = mHoldCall;
        if (call != null) {
            mHoldCall = null;
            call.release();
        }

        if (mShutdownTimer != null) {
            mShutdownTimer.cancel(false);
            mShutdownTimer = null;
        }

        mExecutor.shutdownNow();

        if (mAudioManagerDevices != null && mAudioManagerDevices.isRunning()) {
            mAudioManagerDevices.stop();
            mAudioManagerDevices = null;
        }

        if (mNetworkLock != null) {
            mNetworkLock.release();
        }

        if (mProcessingLock != null) {
            mProcessingLock.release();
        }
    }

    private void initialize() {

        if (mTwinmeContext != null) {

            return;
        }

        mTwinmeApplication = TwinmeApplicationImpl.getInstance(this);
        if (mTwinmeApplication == null) {

            return;
        }

        mTwinmeContext = mTwinmeApplication.getTwinmeContext();
        if (mTwinmeContext == null) {

            return;
        }

        // Get the power processing lock to tell the system we need the CPU.
        mProcessingLock = mTwinmeApplication.allocateProcessingLock();

        // We also need the network for the lifetime of this service.
        mNetworkLock = mTwinmeApplication.allocateNetworkLock();

        mNotificationCenter = mTwinmeContext.getNotificationCenter();
        mServiceNotification = mNotificationCenter.getPlaceholderCallNotification();

        mConnected = false;

        mAudioManagerDevices = TwinmeAudioManager.create(getApplicationContext(), mTwinmeContext);

        mTwinmeContext.setObserver(mTwinmeContextObserver);

        // If we are ready, don't wait for the Twinlife executor to call onTwinlifeReady() because we can receive an intent.
        if (mTwinmeContext.hasTwinlife()) {
            onTwinlifeReady();
        }

        // If we are not connected, force an immediate connection retry.
        if (!mTwinmeContext.isConnected()) {
            mTwinmeContext.connect();
        }

        if (FeatureUtils.isTelecomSupported(getApplicationContext())) {
            mTelecomManager = getApplicationContext().getSystemService(TelecomManager.class);
            mPhoneAccountHandle = TelecomUtils.getPhoneAccountHandle(getApplicationContext());
        }
    }

    private synchronized boolean shouldCallStartForeground(@Nullable CallState call, int serviceTypes) {
        if (mPreviousServiceTypes != serviceTypes) {
            return true;
        }

        CallStatus status = call != null ? call.getStatus() : null;

        if (status != mPreviousCallStatus) {
            return true;
        }

        return false;
    }

    public void startNotification() {
        startNotification(false);
    }

    public void startNotification(boolean forceStartForeground) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startNotification");
        }

        CallState call;
        Notification notification;

        synchronized (this) {
            call = getActiveCall();
            notification = mServiceNotification;
        }

        // -1 = FOREGROUND_SERVICE_TYPE_MANIFEST, i.e. all types declared in the manifest.
        // Won't actually be used because we don't use service types for SDK < R
        int serviceType = -1;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;

            if (mActivityStarted.get() && call != null && CallStatus.isActive(call.getStatus())) {
                /* CallActivity has been started and displayed => we're in the foreground => we can
                   ask for the microphone & camera permissions.
                   If we do it too soon, Android refuses to promote the service.
                   If we don't do it at all, the microphone & camera are disabled if we put the app in the background.
                 */
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
        }

        if (!forceStartForeground && !shouldCallStartForeground(call, serviceType)) {
            return;
        }

        if (notification != null) {
            startForegroundCompat(mNotificationId, notification, serviceType);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || getActiveCall() == null) {
            startForegroundCompat(mNotificationId, mNotificationCenter.getPlaceholderCallNotification(), serviceType);
            finish();
        }

        synchronized (this) {
            mPreviousCallStatus = call != null ? call.getStatus() : null;
        }
    }

    private void startForegroundCompat(int notificationId, Notification notification, int serviceTypes) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(notificationId, notification, serviceTypes);
                synchronized (this) {
                    mPreviousServiceTypes = serviceTypes;
                }
            } catch (Exception e) {
                // Most likely because Android refused the microphone/camera service types, either because
                // it considers we're still in background, or the app hasn't been granted the microphone & camera permissions yet =>
                // retry without it.
                if ((serviceTypes & (ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)) != 0) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "Could not startForeground with microphone & camera service type, retrying without it.", e);
                    }

                    int typesWithoutMicrophone = serviceTypes & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                    try {
                        startForeground(notificationId, notification, typesWithoutMicrophone);
                        synchronized (this) {
                            mPreviousServiceTypes = typesWithoutMicrophone;
                        }
                    } catch (Exception e2) {
                        Log.e(LOG_TAG, "Could not startForeground with service types " + typesWithoutMicrophone + ", giving up.", e2);
                        mTwinmeContext.exception(CallAssertPoint.START_FOREGROUND_FAILURE, e2, null);
                    }
                }
            }
        } else {
            startForeground(notificationId, notification);
            synchronized (this) {
                mPreviousServiceTypes = serviceTypes;
            }
        }
    }

    private synchronized void onActionIncomingCall(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionIncomingCall intent=" + intent);
        }

        final UUID peerConnectionId = (UUID) intent.getSerializableExtra(CallService.PARAM_PEER_CONNECTION_ID);
        final CallStatus callStatus = (CallStatus) intent.getSerializableExtra(PARAM_CALL_MODE);
        final boolean transfer = intent.getBooleanExtra(PARAM_TRANSFER, false);

        Originator originator;
        Bitmap avatar;
        CallStatus initialStatus;
        synchronized (sLock) {
            if (sNotificationInfo != null) {
                originator = sNotificationInfo.originator;
                avatar = sNotificationInfo.avatar;
                initialStatus = sNotificationInfo.callStatus;
            } else {
                originator = null;
                avatar = null;
                initialStatus = null;
            }
        }

        if (originator == null) {
            mTwinmeContext.assertion(CallAssertPoint.NO_NOTIFICATION_INFO, AssertPoint.create(getClass()));
            return;
        }

        final UUID originatorId;
        final UUID groupId;

        if (originator instanceof Group) {
            originatorId = originator.getTwincodeOutboundId();
            groupId = originator.getId();
        } else if (originator instanceof GroupMember) {
            originatorId = originator.getId();
            groupId = ((GroupMember)originator).getGroup().getId();
        } else {
            originatorId = originator.getId();
            groupId = null;
        }

        if (peerConnectionId == null || originatorId == null || mPeerConnectionService == null || callStatus == null || mPeerCallService == null) {

            startNotification();
            return;
        }

        final CallConnection existingCallConnection = findPeerConnection(peerConnectionId);
        if (existingCallConnection != null) {

            startNotification();
            return;
        }

        CallState call = mActiveCall;
        CallState holdCall = mHoldCall;

        if (call != null && call.getStatus() != CallStatus.TERMINATED) {
            if (handleIncomingCallDuringActiveCall(call, peerConnectionId, originatorId, groupId, callStatus, transfer)) {
                // The caller will be added to the current call.
                startNotification();
                return;
            } else if (holdCall != null) {
                if (handleIncomingCallDuringActiveCall(holdCall, peerConnectionId, originatorId, groupId, callStatus, transfer)) {
                    // The caller will be added to the hold call.
                    startNotification();
                    return;
                }
                // We're already in a double call, reject the new one.
                mPeerConnectionService.terminatePeerConnection(peerConnectionId, TerminateReason.BUSY);
            }
        }

        if (mPeerConnectionService.listenPeerConnection(peerConnectionId, this) != ErrorCode.SUCCESS) {

            return;
        }

        mNotificationId = CALL_SERVICE_INCOMING_NOTIFICATION_ID;

        mServiceNotification = mNotificationCenter.createIncomingCallNotification(originator, avatar, initialStatus, call == null ? null : call.getId());

        // Invalidate the shutdown timer: we have a new call.
        if (mShutdownTimer != null) {
            mShutdownTimer.cancel(false);
            mShutdownTimer = null;
        }

        call = new CallState(this, mPeerCallService, originatorId, groupId, true);
        call.setAudioVideoState(callStatus);

        if (mActiveCall == null) {
            mActiveCall = call;
        } else {
            mHoldCall = call;
        }

        if (transfer) {
            // Transfer to the browser is handled in handleIncomingCallDuringExistingCall
            call.setTransferDirection(TO_DEVICE);
        }

        final CallConnection callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                call, peerConnectionId, callStatus, null);
        callConnection.checkOperation(ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION);

        mPeers.put(peerConnectionId, callConnection);
        mCallsContacts.put(originatorId, call);

        call.addPeerConnection(callConnection);

        // Must be called from the main UI thread and audio must be setup before ringing
        // (the video will be setup only if we accept the call).
        if (callStatus == CallStatus.INCOMING_VIDEO_BELL) {
            setupVideo(callConnection.getMainParticipant());
        }

        // Setup the audio from the main thread but after having processed the service
        // because this could be slow and we should not block for too long.
        uiThreadHandler.post(this::setupAudio);

        startNotification();

        final UUID callId = call.getId();

        mExecutor.execute(() -> {

            final Capabilities capabilities = originator.getIdentityCapabilities();
            Schedule schedule = capabilities.getSchedule();

            if (schedule != null && !schedule.isNowInRange()) {
                callConnection.terminate(TerminateReason.SCHEDULE);
                return;
            }

            // Discreet relation: do not create the CallDescriptor.
            if (capabilities.hasDiscreet()) {
                CallState callState = callConnection.getCall();
                callState.checkOperation(CallOperation.START_CALL);
                callState.checkOperation(CallOperation.START_CALL_DONE);
            }

            setOriginator(callConnection, originator);
            onOperation(callConnection);
            if (capabilities.hasAutoAnswerCall()) {
                // The onActionAcceptCall() must be executed from the main UI thread.
                uiThreadHandler.post(() -> {
                    final Intent serviceIntent = new Intent(this, CallService.class);
                    serviceIntent.setAction(CallService.ACTION_ACCEPT_CALL);
                    onActionAcceptCall(serviceIntent);
                    final Intent acceptCallIntent = mNotificationCenter.createAcceptCallIntent(callStatus, originator, callId);
                    acceptCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(acceptCallIntent);
                });
            } else {
                mPeerConnectionService.sendDeviceRinging(peerConnectionId);
            }

            if (!capabilities.hasAutoAnswerCall()) {
                // Auto-accept: sometimes stopRingtone() in onActionAcceptCall() is called
                // before uiThreadHandler runs this lambda,
                // so we need to check whether we should really ring.
                startIncomingCallRingtone(callConnection);
            }
        });

        callConnection.setTimer(mExecutor, this::callTimeout, CALL_INCOMING_TIMEOUT, callStatus);
    }

    void startIncomingCallRingtone(@NonNull CallConnection callConnection) {
        // Start incoming ringtone and vibration in another thread because this can block the UI thread.
        startRingtone(callConnection.isVideo() ? RingtoneSoundType.RINGTONE_INCOMING_VIDEO_CALL : RingtoneSoundType.RINGTONE_INCOMING_AUDIO_CALL);
    }

    private boolean handleIncomingCallDuringActiveCall(@NonNull CallState call, @NonNull UUID peerConnectionId, @NonNull UUID originatorId,
                                                       @Nullable UUID groupId, @NonNull CallStatus callStatus, boolean transfer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "handleIncomingCallDuringActiveCall: call=" + call + " peerConnectionId=" + peerConnectionId
                    + " originatorId=" + originatorId + " groupId=" + groupId + " callStatus=" + callStatus + " transfer=" + transfer);
        }

        if (mPeerConnectionService == null) {
            return false;
        }

        if (transfer) {
            final CallConnection callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                    call, peerConnectionId, callStatus.toAccepted(), null);

            mPeers.put(peerConnectionId, callConnection);
            call.addPeerConnection(callConnection);

            call.setTransferDirection(TO_BROWSER);
            onTransferRequest(peerConnectionId, originatorId);
            return true;
        } else if (call.autoAcceptNewParticipant(originatorId, groupId)) {
            final CallStatus connectionStatus = (call.isVideo() ? CallStatus.ACCEPTED_INCOMING_VIDEO_CALL : CallStatus.ACCEPTED_INCOMING_CALL).toAccepted();
            final CallConnection callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                    call, peerConnectionId, connectionStatus, null);
            callConnection.checkOperation(ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION);
            mPeers.put(peerConnectionId, callConnection);
            call.addPeerConnection(callConnection);

            CallStatus currentCallStatus = call.getStatus();

            if (!Arrays.asList(CallStatus.IN_CALL, CallStatus.IN_VIDEO_CALL, CallStatus.ON_HOLD).contains(currentCallStatus)) {
                call.addIncomingGroupCallConnection(callConnection);
                return true;
            }

            mTwinmeContext.getOriginator(originatorId, groupId, (ErrorCode errorCode, Originator originator) -> {
                if (errorCode == ErrorCode.SUCCESS && originator != null) {
                    setOriginator(callConnection, originator);

                    // The peer is joining an existing group call => invite immediately
                    if (originator.getType() == Originator.Type.GROUP_MEMBER &&
                            (callConnection.checkOperation(ConnectionOperation.INVITE_CALL_ROOM))) {
                        // Workaround: at this point we haven't initialized the peer version with its offer yet,
                        // but we need it for the callConnection.isGroupSupported() check in inviteCallRoom().
                        // This is harmless since we know the peer supports group calls (he wouldn't be able to call us otherwise),
                        // and the correct version will soon be extracted from the offer.
                        callConnection.setPeerVersion(new Version(PeerConnectionService.MAJOR_VERSION, PeerConnectionService.MINOR_VERSION));
                        call.inviteCallRoom(newOperation(callConnection, ConnectionOperation.INVITE_CALL_ROOM), callConnection);
                    }

                    callConnection.setTimer(mExecutor, this::callTimeout, CONNECT_TIMEOUT, callConnection.getStatus().toAccepted());
                } else {
                    onError(callConnection, ConnectionOperation.GET_CONTACT, errorCode, null);
                }
                onOperation(callConnection);
            });
            return true;
        }

        return false;
    }

    private void onTransferRequest(UUID peerConnectionId, UUID originatorId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTransferRequest peerConnectionId=" + peerConnectionId + ", originatorId=" + originatorId);
        }
        mTwinmeContext.getCallReceiver(originatorId, (ErrorCode errorCode, CallReceiver callReceiver) -> {
            if (callReceiver == null || !callReceiver.getCapabilities().hasTransfer()) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Transfer request received on invalid CallReceiver: " + callReceiver);
                }
                return;
            }
            synchronized (this) {
                CallConnection callConnection = mPeers.get(peerConnectionId);
                if (callConnection != null) {
                    callConnection.setOriginator(callReceiver, null, null, null);
                }
            }

            Intent intent = new Intent(Intents.INTENT_CALL_SERVICE_MESSAGE);
            intent.putExtra(CALL_SERVICE_EVENT, MESSAGE_TRANSFER_REQUEST);
            intent.putExtra(CALL_CONTACT_ID, originatorId);
            intent.putExtra(PARAM_PEER_CONNECTION_ID, peerConnectionId);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        });
    }

    private synchronized void onActionOutgoingCall(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionOutgoingCall intent=" + intent);
        }

        final UUID contactId = (UUID) intent.getSerializableExtra(PARAM_CONTACT_ID);
        final UUID groupId = (UUID) intent.getSerializableExtra(PARAM_GROUP_ID);

        final CallStatus askCallStatus = (CallStatus) intent.getSerializableExtra(PARAM_CALL_MODE);
        final boolean addParticipant = intent.getBooleanExtra(PARAM_CALL_ADD_PARTICIPANT, false);

        if (mPeerConnectionService == null || askCallStatus == null || mPeerCallService == null || contactId == null) {
            startNotification();
            return;
        }

        // A call is already in progress or is being finished, send the current state so that the UI can be updated.
        CallState call = mActiveCall;
        if (call != null && call.getStatus() != CallStatus.TERMINATED && !addParticipant) {
            startNotification();
            sendError(ErrorType.CALL_IN_PROGRESS, call);
            return;
        }

        // Before we leave this function, we must have a valid mActiveCall otherwise the service could be stopped.
        final CallStatus callStatus;
        if (call == null) {
            call = new CallState(this, mPeerCallService, contactId, groupId, false);
            callStatus = askCallStatus;
            call.setAudioVideoState(callStatus);
            mActiveCall = call;
            mCallsContacts.put(contactId, call);
        }

        // Invalidate the shutdown timer immediately: we have a new call.
        if (mShutdownTimer != null) {
            mShutdownTimer.cancel(false);
            mShutdownTimer = null;
        }
        if (call.isVideo()) {
            setupLocalVideo(call);
        }
        startNotification();

        uiThreadHandler.post(this::setupAudio);

        // Avoid executing startGroupCall() and startContactCall() from the main UI thread
        // because we are holding a lock on CallService.
        if (groupId != null) {
            mTwinmeContext.execute(() -> startGroupCall(groupId, askCallStatus));
        } else {
            mTwinmeContext.execute(() -> startContactCall(contactId, askCallStatus, addParticipant));
        }
    }

    private void startContactCall(UUID contactId, CallStatus callStatus, boolean addParticipant) {
        mTwinmeContext.getContact(
                contactId,
                (ErrorCode errorCode, Contact contact) -> startCall(contact, callStatus, addParticipant)
        );
    }

    private void startGroupCall(UUID groupId, CallStatus askCallStatus) {

        mTwinmeContext.getGroup(groupId, (ErrorCode errorCode, Group group) -> {

            if (errorCode != ErrorCode.SUCCESS || group == null) {
                onError(null, ConnectionOperation.GET_CONTACT, errorCode, null);
                return;
            }

            mTwinmeContext.listGroupMembers(group, ConversationService.MemberFilter.JOINED_MEMBERS, (ErrorCode listErrorCode, List<GroupMember> members) -> {
                if (listErrorCode == ErrorCode.SUCCESS && members != null) {
                    startGroupCall(group, members, askCallStatus);
                } else {
                    onError(null, ConnectionOperation.GET_CONTACT, errorCode, null);
                }
            });
        });
    }

    private void startGroupCall(@NonNull Originator originator, @NonNull List<GroupMember> members, CallStatus askCallStatus) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startGroupCall members=" + members + " askCallStatus=" + askCallStatus);
        }

        if (mPeerConnectionService == null || askCallStatus == null || mPeerCallService == null) {
            return;
        }

        // The call must exist as it should have been created by onActionOutgoingCall().
        final CallState call = getActiveCall();
        if (call == null) {
            return;
        }

        // Discreet relation: do not create the CallDescriptor.
        if (originator.getIdentityCapabilities().hasDiscreet()) {
            call.checkOperation(CallOperation.START_CALL);
            call.checkOperation(CallOperation.START_CALL_DONE);
        }

        final CallStatus callStatus;
        if (call.isVideoSourceOn()) {
            callStatus = CallStatus.OUTGOING_VIDEO_CALL;
        } else {
            callStatus = askCallStatus;
        }

        // Create the CallConnection for each group member (according to its schedule).
        // Do not create the P2P connection yet until we have filled the CallState with every member.
        final List<CallConnection> connections = new ArrayList<>();
        for (GroupMember groupMember : members) {
            Schedule schedule = groupMember.getCapabilities().getSchedule();

            if (schedule != null && !schedule.isNowInRange()){
                continue;
            }

            final CallConnection callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                    call, null, callStatus, null);

            call.addPeerConnection(callConnection);
            setOriginator(callConnection, groupMember);
            connections.add(callConnection);
        }

        if (!CallStatus.isActive(call.getStatus())) {
            // Play the connecting tone for a new outgoing call in a separate thread to avoid blocking the UI.
            // Important note: schedule that call from the main UI thread so that we schedule
            // it after the setupAudio() has finished.
            startRingtone(RingtoneSoundType.RINGTONE_OUTGOING_CALL_CONNECTING);
        }

        if (mCallNotifications.get(call.getId()) == null) {
            mServiceNotification = mNotificationCenter.createOutgoingCallNotification(originator, callStatus, call.getId());
            mCallNotifications.put(call.getId(), mServiceNotification);
        }

        startNotification();

        // Start the call connection once we know every member.
        for (CallConnection callConnection : connections) {
            // Must be called from the main UI thread.
            uiThreadHandler.post(() -> setupVideo(callConnection.getMainParticipant()));

            callConnection.setTimer(mExecutor, this::callTimeout, CALL_OUTGOING_TIMEOUT, callStatus);

            onOperation(callConnection);
        }
    }

    private void startCall(Originator originator, CallStatus askCallStatus, boolean addParticipant) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startCall originator=" + originator + " askCallStatus=" + askCallStatus + " addParticipant=" + addParticipant);
        }

        if (originator == null || mPeerConnectionService == null || askCallStatus == null || mPeerCallService == null) {
            return;
        }

        // The call must exist as it should have been created by onActionOutgoingCall().
        final CallState call = getActiveCall();
        if (call == null) {
            return;
        }

        // Discreet relation: do not create the CallDescriptor.
        if (originator.getIdentityCapabilities().hasDiscreet()) {
            call.checkOperation(CallOperation.START_CALL);
            call.checkOperation(CallOperation.START_CALL_DONE);
        }

        final CallStatus callStatus;
        if (call.isVideoSourceOn()) {
            callStatus = CallStatus.OUTGOING_VIDEO_CALL;
        } else {
            callStatus = askCallStatus;
        }

        final CallConnection callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                call, null, callStatus, null);

        call.addPeerConnection(callConnection);

        Schedule schedule = originator.getCapabilities().getSchedule();

        if(schedule != null && !schedule.isNowInRange()){
            // callConnection doesn't have a peerConnectionId yet,
            // so we can't use CallConnection.terminate().
            onTerminatePeerConnection(callConnection, TerminateReason.SCHEDULE);
            return;
        }

        if (!CallStatus.isActive(call.getStatus())) {
            // Play the connecting tone for a new outgoing call in a separate thread to avoid blocking the UI.
            // Important note: schedule that call from the main UI thread so that we schedule
            // it after the setupAudio() has finished.
            startRingtone(RingtoneSoundType.RINGTONE_OUTGOING_CALL_CONNECTING);
        }

        // Must be called from the main UI thread.
        uiThreadHandler.post(() -> setupVideo(callConnection.getMainParticipant()));

        callConnection.setTimer(mExecutor, this::callTimeout, CALL_OUTGOING_TIMEOUT, callStatus);

        if (mCallNotifications.get(call.getId()) == null) {
            mServiceNotification = mNotificationCenter.createOutgoingCallNotification(originator, callStatus, call.getId());
            mCallNotifications.put(call.getId(), mServiceNotification);
        }

        startNotification();

        setOriginator(callConnection, originator);
        onOperation(callConnection);
    }

    private synchronized void onActionAcceptCall(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionAcceptCall intent=" + intent);
        }

        final CallState call = mHoldCall != null ? mHoldCall : mActiveCall;
        if (call == null) {

            return;
        }

        final CallStatus mode = call.getStatus();
        if (!CallStatus.isIncoming(mode)) {
            sendError(ErrorType.CALL_IN_PROGRESS, call);
            return;
        }

        final CallConnection callConnection = call.getInitialConnection();
        if (callConnection == null) {
            sendError(ErrorType.CALL_IN_PROGRESS, call);

            return;
        }
        mNotificationId = CALL_SERVICE_INCALL_NOTIFICATION_ID;

        // Must be called from the main UI thread.  The audio was setup by onActionIncomingCall().
        setupVideo(callConnection.getMainParticipant());

        if (mode == CallStatus.INCOMING_VIDEO_BELL) {
            //Activate audio and video
            call.setAudioVideoState(CallStatus.ACCEPTED_INCOMING_VIDEO_CALL);

            callConnection.initSources(getEGLContext(), CallStatus.ACCEPTED_INCOMING_CALL);
            onChangeConnectionState(callConnection, callConnection.getConnectionState());
        } else {

            stopRingtone();

            callConnection.setTimer(mExecutor, this::callTimeout, CONNECT_TIMEOUT, mode.toAccepted());

            NotificationManagerCompat.from(mTwinmeApplication).cancel(CALL_SERVICE_INCOMING_NOTIFICATION_ID);

            Originator originator = callConnection.getOriginator();
            if (originator != null) {
                mServiceNotification = mNotificationCenter.createCallNotification(callConnection.getStatus(), originator, call.getId(), call.isAudioSourceOn());
                mCallNotifications.put(call.getId(), mServiceNotification);
                startNotification();
            }

            onOperation(callConnection);
        }

        if (call == mHoldCall) {
            switchCall();
        }
    }

    private synchronized void onActionStartStreaming(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStartStreaming intent=" + intent);
        }

        final String path = intent.getStringExtra(PARAM_STREAMING_PATH);
        final CallState call = getActiveCall();
        final MediaMetaData mediaMetaData = intent.getParcelableExtra(PARAM_STREAMING_INFO);
        if (call == null || path == null || mediaMetaData == null) {

            return;
        }

        final Uri uri = Uri.parse(path);
        if (mediaMetaData.artworkUri != null && mediaMetaData.artwork == null) {
            Bitmap artwork;
            try {
                artwork = MediaStore.Images.Media.getBitmap(getContentResolver(), mediaMetaData.artworkUri);
            } catch (Exception | OutOfMemoryError exception) {
                artwork = null;
            }
            mediaMetaData.artwork = artwork;
        }
        call.startStreaming(getContentResolver(), uri, mediaMetaData, mExecutor);

    }

    private synchronized void onActionStopStreaming(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStopStreaming intent=" + intent);
        }

        final CallState call = getActiveCall();
        if (call == null) {

            return;
        }

        call.stopStreaming(true);
    }

    private synchronized void onActionSwitchCall(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionSwitchCall intent=" + intent);
        }

        switchCall();
    }

    private synchronized void onActionHoldCall(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionHoldCall intent=" + intent);
        }

        final CallState call = getActiveCall();
        if (call == null || call.getStatus() == CallStatus.ON_HOLD) {
            return;
        }

        call.putOnHold();

        sendMessage(MESSAGE_CALL_ON_HOLD, call);
    }

    private synchronized void onActionResumeCall(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionHoldCall intent=" + intent);
        }

        final CallState call = getActiveCall();
        if (call == null || call.getStatus() != CallStatus.ON_HOLD) {
            return;
        }

        call.resume();

        sendMessage(MESSAGE_CALL_RESUMED, call);
    }

    /**
     * Moves all participants from {@link CallService#mActiveCall} to {@link CallService#mHoldCall},
     * creating a call room if necessary, then terminates {@link CallService#mActiveCall},
     * switching calls in the process.
     *
     * @param intent unused for now
     */
    private synchronized void onActionMergeCalls(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionMergeCalls intent=" + intent);
        }

        mergeCalls();
    }

    private synchronized void mergeCalls(){
        final CallState call = getActiveCall();
        final CallState hold = getHoldCall();
        if (call == null || hold == null) {
            return;
        }

        for (CallConnection connection : hold.getConnections()) {
            if (connection.getOriginator() != null) {
                mCallsContacts.put(connection.getOriginator().getId(), call);
            }
            connection.setCall(call);
            onChangeConnectionState(connection, connection.getConnectionState());
            connection.initSources();
            connection.sendResumeCallIQ();
        }

        hold.clearConnections();

        terminateCall(hold, TerminateReason.MERGE, hold.getStatus());

        sendMessage(MESSAGE_CALLS_MERGED);
    }

    private synchronized void onActionTerminateCall(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionTerminateCall: intent=" + intent);
        }

        final UUID callId = (UUID) intent.getSerializableExtra(PARAM_CALL_ID);
        final UUID peerConnectionId = (UUID) intent.getSerializableExtra(PARAM_PEER_CONNECTION_ID);
        final TerminateReason terminateReason = (TerminateReason) intent.getSerializableExtra(PARAM_TERMINATE_REASON);

        if (terminateReason == null) {

            return;
        }

        final CallState activeCall = getActiveCall();
        final CallState holdCall = getHoldCall();

        final CallState call;

        if (holdCall != null && holdCall.getId().equals(callId)) {
            call = holdCall;
        } else if (holdCall != null && peerConnectionId != null && holdCall.getConnectionById(peerConnectionId) != null) {
            // peerConnectionId != null => Intent was sent by TelecomConnectionService.
            call = holdCall;
        } else {
            call = activeCall;
        }

        if (call == null) {

            return;
        }

        if (peerConnectionId == null && call.getCallRoomId() != null) {
            call.leaveCallRoom(mTwinmeContext.newRequestId());
        }

        final List<CallConnection> connections = call.getConnections();
        for (CallConnection callConnection : connections) {
            if (callConnection.getStatus() != CallStatus.TERMINATED
                    && (peerConnectionId == null || peerConnectionId.equals(callConnection.getPeerConnectionId()))) {
                callConnection.terminate(terminateReason);

                onTerminatePeerConnection(callConnection, terminateReason);
            }
        }

        if (call == activeCall && holdCall != null) {
            switchCall();
        }
    }

    private synchronized void onActionCallQuality(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCallQuality intent=" + intent);
        }

        if (mPeerConnectionIdTerminated != null && mPeerConnectionService != null) {
            int quality = intent.getIntExtra(PARAM_CALL_QUALITY, 4);
            mPeerConnectionService.sendCallQuality(mPeerConnectionIdTerminated, quality);
        }
    }

    private void onActionAudioMute(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionAudioMute: intent=" + intent);
        }

        final CallState call = getActiveCall();
        if (call == null) {

            return;
        }

        mAudioMute = intent.getBooleanExtra(PARAM_AUDIO_MUTE, false);

        call.setAudioSourceOn(!mAudioMute);

        // Mute every active audio connection.
        final List<CallConnection> connections = call.getConnections();
        for (CallConnection connection : connections) {
            if (!call.getStatus().isOnHold() && !connection.getStatus().isOnHold()) {
                connection.setAudioDirection(mAudioMute ? RtpTransceiverDirection.RECV_ONLY : RtpTransceiverDirection.SEND_RECV);
            }
        }

        // Toggle the notification's mute action.
        Originator originator = call.getOriginator();
        if (CallStatus.isActive(call.getStatus()) && originator != null) {
            mServiceNotification = mNotificationCenter.createCallNotification(call.getStatus(), originator, call.getId(), call.isAudioSourceOn());
            startNotification(true);
        }

        sendMessage(MESSAGE_AUDIO_MUTE_UPDATE);
    }

    private void onActionSpeaker(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionSpeaker: intent=" + intent);
        }

        final CallState call = mActiveCall;
        if (call == null) {
            return;
        }

        boolean audioSpeaker = intent.getBooleanExtra(PARAM_AUDIO_SPEAKER, false);

        setSpeaker(audioSpeaker, true);
    }

    private void onActionSwitchCallMode(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionSwitchCallMode: intent=" + intent);
        }

        final CallState call = mActiveCall;
        if (call == null || mPeerConnectionService == null) {

            return;
        }

        final List<CallConnection> connections = call.getConnections();
        final boolean video = call.isVideoSourceOn();
        for (CallConnection callConnection : connections) {

            // Must be called from the main UI thread.  The audio was setup by onActionIncomingCall().
            if (video) {
                setupVideo(callConnection.getMainParticipant());
            }
            callConnection.initSources();
        }
    }

    private void onActionCheckState(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCheckState: intent=" + intent);
        }

        sendMessage(MESSAGE_STATE);
    }

    private void onActionSwitchCamera(Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionSwitchCamera: intent=" + intent);
        }

        // We can receive an action while are not in a call and it must be rejected.
        final CallState call = mActiveCall;
        if (call == null || !call.isConnected() || mPeerConnectionService == null) {

            return;
        }

        mPeerConnectionService.switchCamera(!call.isFrontCamera(), this::onCameraSwitch);
    }

    private void onActionCameraMute(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionCameraMute: intent=" + intent);
        }

        final CallState call = mActiveCall;
        if (call == null || mPeerConnectionService == null) {

            return;
        }

        mIsCameraMute = intent.getBooleanExtra(PARAM_CAMERA_MUTE, false);

        call.setVideoSourceOn(!mIsCameraMute);
        final EglBase.Context eglContext = call.isVideoSourceOn() ? getEGLContext() : null;
        if (call.isVideoSourceOn()) {
            setupLocalVideo(call);
        }

        // Mute every active video connection.
        final List<CallConnection> connections = call.getConnections();
        for (CallConnection connection : connections) {
            if (!call.getStatus().isOnHold() && !connection.getStatus().isOnHold()) {
                if (call.isVideoSourceOn()) {
                    setupVideo(connection.getMainParticipant());
                }
                // Same result as calling connection.setVideoDirection(),
                // But we need to pass the EGLContext in case the camera is enabled for the first time.
                connection.initSources(eglContext, connection.getStatus().toVideo());
            }
        }

        sendMessage(MESSAGE_CAMERA_MUTE_UPDATE);
    }

    private synchronized void onActionAcceptTransfer(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionAcceptTransfer intent=" + intent);
        }

        final CallState call = getActiveCall();
        if (call == null) {

            return;
        }

        final UUID peerConnectionId = (UUID) intent.getSerializableExtra(PARAM_PEER_CONNECTION_ID);

        if (peerConnectionId == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "PARAM_PEER_CONNECTION_ID not found in intent");
            }
            return;
        }

        final CallConnection callConnection = call.getConnectionById(peerConnectionId);

        if (callConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Could not find peer connection with ID: " + peerConnectionId);
            }
            return;
        }
        mNotificationId = CALL_SERVICE_INCALL_NOTIFICATION_ID;

        callConnection.setTimer(mExecutor, this::callTimeout, CONNECT_TIMEOUT, callConnection.getStatus());

        onOperation(callConnection);

    }

    private synchronized void onActionStartKeyCheck(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStartKeyCheck intent=" + intent);
        }

        final Locale language = (Locale) intent.getSerializableExtra(PARAM_KEY_CHECK_LANGUAGE);

        startKeyCheck(language);
    }

    private synchronized void onActionStopKeyCheck(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionStopKeyCheck intent=" + intent);
        }

        stopKeyCheck();
    }

    private synchronized void onActionWordCheckResult(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onActionWordCheckResult intent=" + intent);
        }

        if (mKeyCheckSessionHandler == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started yet, aborting");
            }
            return;
        }

        if (!(intent.hasExtra(PARAM_WORD_CHECK_INDEX) && intent.hasExtra(PARAM_WORD_CHECK_RESULT))) {
            if (DEBUG) {
                Log.d(LOG_TAG, "PARAM_WORD_CHECK_RESULT and PARAM_WORD_CHECK_INDEX are mandatory, aborting");
            }
            return;
        }

        final int wordIndex = intent.getIntExtra(PARAM_WORD_CHECK_INDEX, -1);
        final boolean result = intent.getBooleanExtra(PARAM_WORD_CHECK_RESULT, false);

        mExecutor.execute(() -> mKeyCheckSessionHandler.processLocalWordCheckResult(new WordCheckResult(wordIndex, result)));
    }


    //
    // Private methods
    //

    private long newOperation(@NonNull CallConnection callConnection, int operationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "newOperation: operationId=" + operationId);
        }

        long requestId = mTwinmeContext.newRequestId();
        synchronized (mConnectionRequestIds) {
            mConnectionRequestIds.put(requestId, new ConnectionOperation(callConnection, operationId));
        }

        return requestId;
    }

    private long newOperation(@NonNull CallState call, int operationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "newOperation: operationId=" + operationId);
        }

        long requestId = mTwinmeContext.newRequestId();
        synchronized (mCallRequestIds) {
            mCallRequestIds.put(requestId, new CallOperation(call, operationId));
        }

        return requestId;
    }

    private synchronized void onOperation(@NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOperation");
        }

        if (!mConnected || mPeerConnectionService == null) {

            return;
        }

        final CallState call = callConnection.getCall();
        final Originator originator = callConnection.getOriginator();
        CallStatus mode = callConnection.getStatus();
        final UUID peerTwincodeOutboundId = callConnection.getPeerTwincodeOutboundId();
        boolean video = call.isVideoSourceOn();
        if (originator != null && peerTwincodeOutboundId != null) {

            UUID twincodeOutboundId = originator.getTwincodeOutboundId();
            UUID twincodeInboundId = originator.getTwincodeInboundId();
            if (twincodeOutboundId != null && twincodeInboundId != null && call.checkOperation(CallOperation.START_CALL)) {

                long requestId = newOperation(call, CallOperation.START_CALL);
                if (DEBUG) {
                    Log.d(LOG_TAG, "PeerConnectionService.startCall: requestId=" + requestId + " originator=" + originator.getId());
                }
                mTwinmeContext.getConversationService().startCall(requestId, originator, video, call.isIncoming());

                if (!CallStatus.isTerminated(mode) && (call.isIncoming() != (CallStatus.isIncoming(mode) || CallStatus.isAccepted(mode)))) {
                    // If we get these reports, it means bug #180 is caused by an inconsistent CallConnection.getStatus().
                    mTwinmeContext.assertion(CallAssertPoint.START_CALL, AssertPoint.create(call.getOriginator())
                            .put(mode).put(call.getStatus()));
                }

                return;
            }
        }

        if (!call.isDoneOperation(CallOperation.START_CALL)) {

            return;
        }

        if (CallStatus.isOutgoing(mode) && peerTwincodeOutboundId != null && originator != null) {
            final TwincodeOutbound twincodeOutbound = originator.getTwincodeOutbound();

            if (twincodeOutbound != null && callConnection.checkOperation(ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION)) {

                boolean videoBell = mode == CallStatus.OUTGOING_VIDEO_BELL;

                TwincodeOutbound peerTwincode = originator.getPeerTwincodeOutbound();
                UUID resourceId = twincodeOutbound.getId();
                String peerId = mTwinmeContext.getTwincodeOutboundService().getPeerId(peerTwincodeOutboundId, resourceId);
                Offer offer = new Offer(true, video, videoBell, true);
                offer.group = call.isGroupCall();
                OfferToReceive offerToReceive = new OfferToReceive(true, video, true);

                if (DEBUG) {
                    Log.d(LOG_TAG, "PeerConnectionService.createOutgoingPeerConnection: peerId=" + peerId + " offer=" + offer +
                            " offerToReceive=" + offerToReceive);
                }
                PushNotificationContent notificationContent;
                if (!video) {
                    notificationContent = new PushNotificationContent(PushNotificationPriority.HIGH, PushNotificationOperation.AUDIO_CALL);

                } else if (videoBell) {
                    notificationContent = new PushNotificationContent(PushNotificationPriority.HIGH, PushNotificationOperation.VIDEO_BELL);

                } else {
                    notificationContent = new PushNotificationContent(PushNotificationPriority.HIGH, PushNotificationOperation.VIDEO_CALL);

                }
                notificationContent.timeToLive = CALL_OUTGOING_TIMEOUT * 1000;
                mPeerConnectionService.createOutgoingPeerConnection(originator, peerTwincode, offer, offerToReceive, notificationContent,
                        callConnection, this, (ErrorCode errorCode, UUID id) ->
                                onCreateOutgoingPeerConnection(errorCode, callConnection, id));
            }
        }

        if (CallStatus.isAccepted(callConnection.getStatus()) && callConnection.checkOperation(ConnectionOperation.CREATE_INCOMING_PEER_CONNECTION)) {
            final UUID peerConnectionId = callConnection.getPeerConnectionId();
            Offer offer = mPeerConnectionService.getPeerOffer(peerConnectionId);
            if (offer == null) {
                offer = new Offer(call.isAudioSourceOn(), video, false, true);
            } else {
                if (Boolean.TRUE.equals(callConnection.isTransfer())) {
                    if (call.getTransferDirection() == TO_BROWSER) {
                        // We're transferring the call to the browser
                        // => override its audio/video settings with our own.
                        offer.video = video;
                        offer.audio = call.isAudioSourceOn();
                    } else {
                        // We're transferring the call to this device
                        // => copy the browser's audio/video settings.
                        call.setVideoSourceOn(offer.video);
                        call.setAudioSourceOn(offer.audio);
                    }
                }

                callConnection.setPeerVersion(offer.version);
            }


            OfferToReceive offerToReceive = new OfferToReceive(true, video, true);

            // For the group call to work with the WebApp, the WebApp has assigned a twincode for the Web client
            // and put it in the resource part.  This allows the group call invitation to be forwarded
            // to the WebApp through the proxy.  It is assigned temporarily to the connection.
            if (originator != null && originator.getType() == Originator.Type.CALL_RECEIVER) {
                String peerId = mPeerConnectionService.getPeerId(peerConnectionId);
                if (peerId != null) {
                    int pos = peerId.indexOf('/');
                    if (pos > 0) {
                        UUID peerTwincodeOut = Utils.toUUID(peerId.substring(pos + 1));
                        if (peerTwincodeOut != null) {
                            callConnection.setPeerTwincodeOutboundId(peerTwincodeOut);
                        }
                    }
                }
            }

            if (DEBUG) {
                Log.d(LOG_TAG, "PeerConnectionService.createIncomingPeerConnection: "
                        + " peerConnectionId=" + peerConnectionId + " offer=" + offer +
                        " offerToReceive=" + offerToReceive);
            }

            final TwincodeOutbound peerTwincode;
            if (originator == null) {
                peerTwincode = null;
            } else if (originator.getType() == Originator.Type.GROUP_MEMBER) {
                peerTwincode = originator.getPeerTwincodeOutbound();
            } else if (originator.getType() != Originator.Type.CALL_RECEIVER) {
                peerTwincode = originator.getPeerTwincodeOutbound();
            } else {
                peerTwincode = null;
            }
            if (originator != null && peerTwincode != null && !callConnection.isInvited()) {
                mPeerConnectionService.createIncomingPeerConnection(peerConnectionId, originator, peerTwincode, offer, offerToReceive,
                        callConnection, this, (ErrorCode errorCode, UUID id) -> {
                    if (errorCode == ErrorCode.SUCCESS) {
                        onCreateIncomingPeerConnection(callConnection, peerConnectionId);
                    } else {
                        onTerminatePeerConnection(callConnection, TerminateReason.fromErrorCode(errorCode));
                    }
                });
            } else {
                mPeerConnectionService.createIncomingPeerConnection(peerConnectionId, offer, offerToReceive,
                        callConnection, this, (ErrorCode errorCode, UUID id) -> {
                    if (errorCode == ErrorCode.SUCCESS) {
                        onCreateIncomingPeerConnection(callConnection, peerConnectionId);
                    } else {
                        onTerminatePeerConnection(callConnection, TerminateReason.fromErrorCode(errorCode));
                    }
                });
            }

            // If the call is in a callroom, we can join it now that the incoming call is accepted.
            final UUID callroomId = call.getCallRoomId();
            if (callroomId != null && originator != null) {
                final UUID twincodeInboundId = originator.getTwincodeInboundId();
                if (twincodeInboundId != null && mPeerCallService != null) {

                    long requestId = newOperation(call, ConnectionOperation.JOIN_CALL_ROOM);
                    mPeerCallService.joinCallRoom(requestId, callroomId, twincodeInboundId, call.getConnectionIds());
                }
            }
        }

        //
        // Last Step
        //
    }

    private void onTwinlifeReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeReady");
        }

        mPeerConnectionService = mTwinmeContext.getPeerConnectionService();
        mPeerConnectionService.addServiceObserver(mPeerConnectionServiceObserver);
        mPeerCallService = mTwinmeContext.getPeerCallService();
        mPeerCallService.addServiceObserver(mPeerCallServiceObserver);
        mTwinmeContext.getConversationService().addServiceObserver(mConversationServiceObserver);
    }

    private void onTwinlifeOnline() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwinlifeOnline");
        }

        mConnected = true;

        final List<CallConnection> connections = getConnections();
        for (CallConnection callConnection : connections) {
            onOperation(callConnection);
        }
    }

    private void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onConnectionStatusChange " + connectionStatus);
        }

        mConnected = connectionStatus == ConnectionStatus.CONNECTED;
    }

    private void onUpdateContact(@NonNull Contact contact) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onUpdateContact: contact=" + contact);
        }

        final CallState call = findOriginatorConnection(contact.getId());
        if (call != null && call.onUpdateContact(contact)) {
            for (CallConnection callConnection : call.getConnections()) {
                onOperation(callConnection);
            }
        }
    }

    private void onCreateCallRoom(@NonNull final CallState callState, @NonNull UUID callRoomId,
                                  @NonNull String memberId, int maxMemberCount) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateCallRoom: callState=" + callState + " callRoomId=" + callRoomId
                    + " memberId=" + memberId + " maxMemberCount=" + maxMemberCount);
        }

        callState.updateCallRoom(callRoomId, memberId, null, maxMemberCount);
    }

    private void onJoinCallRoom(@NonNull final CallState callState, @NonNull UUID callRoomId,
                                @NonNull String memberId, @NonNull List<PeerCallService.MemberInfo> members) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onJoinCallRoom: callState=" + callState + " callRoomId=" + callRoomId
                    + " memberId=" + memberId + " members=");
            for (PeerCallService.MemberInfo member : members) {
                Log.d(LOG_TAG, "\t" + member.memberId + ", " + member.p2pSessionId + ", " + member.status);
            }
        }

        if (mPeerConnectionService == null) {

            return;
        }

        final boolean video = callState.isVideoSourceOn();
        final CallStatus mode = video ? CallStatus.OUTGOING_VIDEO_CALL : CallStatus.OUTGOING_CALL;
        callState.updateCallRoom(callRoomId, memberId, members);
        for (PeerCallService.MemberInfo member : members) {
            List<CallConnection> connections = getConnections();
            if (member.status != PeerCallService.MemberStatus.NEW_MEMBER_NEED_SESSION) {

                CallConnection callConnection = findPeerConnection(member.p2pSessionId);
                if (callConnection != null) {
                    // We have a P2P connection with this member, make sure we don't invite it again.
                    callConnection.setCallMemberId(member.memberId);
                    callConnection.checkOperation(ConnectionOperation.INVITE_CALL_ROOM);

                    // Cleanup: sometimes 2 peers in a group call will establish several P2P connections between themselves,
                    // for example when re-joining a group call.
                    // As a workaround, we check if we have other connections with the member and cancel them.
                    for (CallConnection connection : connections) {
                        if (member.memberId.equals(connection.getCallMemberId()) && connection.getPeerConnectionId() != null && !connection.getPeerConnectionId().equals(callConnection.getPeerConnectionId())) {
                            connection.terminate(TerminateReason.CANCEL);
                        }
                    }

                }
                continue;
            }

            // If we already have a P2P session with the new member, do nothing (it is probably starting).
            if (callState.hasConnection(member.memberId)) {
                continue;
            }

            final String peerId = member.memberId;
            CallConnection callConnection = new CallConnection(mPeerConnectionService, mTwinmeContext.getSerializerFactory(),
                    callState, null, mode, peerId);
            // We have no info on the participant yet so we want to display no name
            // and the default avatar until we get the ParticipantInfoIQ.
            callConnection.getMainParticipant().setInformation(null, null, mTwinmeContext.getDefaultAvatar(), null);
            callState.addPeerConnection(callConnection);
            if (!callConnection.checkOperation(ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION)) {

                continue;
            }

            if (video) {
                // Setup the video renderer on the call participant from the main thread.
                uiThreadHandler.post(() -> setupVideo(callConnection.getMainParticipant()));
            }

            Offer offer = new Offer(callState.isAudioSourceOn(), callState.isVideoSourceOn(), false, true);
            offer.group = true;
            OfferToReceive offerToReceive = new OfferToReceive(true, video, true);

            if (DEBUG) {
                Log.d(LOG_TAG, "PeerConnectionService.createOutgoingPeerConnection: peerId=" + peerId + " offer=" + offer +
                        " offerToReceive=" + offerToReceive);
            }
            PushNotificationContent notificationContent;
            if (!video) {
                notificationContent = new PushNotificationContent(PushNotificationPriority.HIGH, PushNotificationOperation.AUDIO_CALL);

            } else {
                notificationContent = new PushNotificationContent(PushNotificationPriority.HIGH, PushNotificationOperation.VIDEO_CALL);

            }

            notificationContent.timeToLive = CALL_OUTGOING_TIMEOUT * 1000;
            mPeerConnectionService.createOutgoingPeerConnection(peerId, offer, offerToReceive, notificationContent,
                    callConnection, this, (ErrorCode errorCode, UUID id) ->
                            onCreateOutgoingPeerConnection(errorCode, callConnection, id));
        }
    }

    private void onMemberJoinCallRoom(UUID callRoomId, String memberId, UUID p2pSession, PeerCallService.MemberStatus status) {
        CallConnection callConnection = findPeerConnection(p2pSession);

        if (callConnection == null) {
            return;
        }

        if (Boolean.TRUE.equals(callConnection.isTransfer()) && callConnection.getCall().getTransferDirection() == TO_BROWSER) {
            // We're transferring our call, and the transfer target has joined the call room.
            callConnection.setTransferToMemberId(memberId);
            // Tell the other participants that they need to transfer us.
            for (CallConnection connection : getConnections()) {
                if (connection.getPeerConnectionId() != null && !connection.getPeerConnectionId().equals(p2pSession)) {
                    connection.sendParticipantTransferIQ(memberId);
                }
            }
        }

    }

    private void onTransferDone(){
        final Intent serviceIntent = new Intent(this, CallService.class);
        serviceIntent.setAction(CallService.ACTION_TERMINATE_CALL);
        serviceIntent.putExtra(CallService.PARAM_TERMINATE_REASON, TerminateReason.TRANSFER_DONE);
        onActionTerminateCall(serviceIntent);
    }

    private void onInviteCallRoom(@NonNull UUID callRoomId, @NonNull UUID twincodeId, @Nullable UUID p2pSession, int maxCount) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onInviteCallRoom: callRoomId=" + callRoomId + " twincodeId=" + twincodeId
                    + " p2pSession=" + p2pSession);
        }

        if (p2pSession == null || mPeerCallService == null) {

            return;
        }

        final CallConnection connection = findPeerConnection(p2pSession);
        if (connection == null) {
            // Check with the active call if this is our call room and we can safely
            // indicate we are joining it again.  The server will send us the members
            // with our P2P sessions (again) and we will establish P2P connections if needed.
            final CallState activeCall = getActiveCall();
            if (activeCall != null && callRoomId.equals(activeCall.getCallRoomId())) {
                final long requestId = newOperation(activeCall, ConnectionOperation.JOIN_CALL_ROOM);
                activeCall.joinCallRoom(requestId, callRoomId, maxCount);
            }
            return;
        }

        // Before joining the call room, check that the associated incoming call is accepted.
        // If not, keep the call room information for later.  We must not join the call room
        // immediately because other participants will connect and establish a P2P connection
        // with us that will be automatically accepted.
        final Originator originator = connection.getOriginator();
        final CallState callState = connection.getCall();
        final CallStatus callStatus = callState.getStatus();
        if (callStatus == CallStatus.INCOMING_CALL || callStatus == CallStatus.INCOMING_VIDEO_CALL || originator == null) {
            // If we already have a CallRoomID we're already in a group call,
            // one of our P2P connection failed so openfire sent us this InviteCallRoomIQ
            // to try and reestablish it.
            // This check is needed because CallConnections in a callroom don't have an originator.
            if (callState.getCallRoomId() == null) {
                callState.joinCallRoom(callRoomId, maxCount);
                return;
            }
        }

        connection.setInvited(true);

        // Special case: reestablish the P2P connection between two members of a group call (see twinme-android-common#54).
        CallState hold = mHoldCall;

        if (hold != null && hold != callState && callRoomId.equals(hold.getCallRoomId())) {
            // We're already in a call in the same callroom
            switchCall();
            mergeCalls();
        }

        final long requestId = newOperation(callState, ConnectionOperation.JOIN_CALL_ROOM);
        callState.joinCallRoom(requestId, callRoomId, maxCount);
    }

    public void onCreateIncomingPeerConnection(@NonNull final CallConnection callConnection,
                                               @NonNull final UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateIncomingPeerConnection: peerConnectionId=" + peerConnectionId);
        }

        CallState call = callConnection.getCall();

        // If the microphone is muted, setup the audio direction accordingly.
        if (!call.isAudioSourceOn() && mPeerConnectionService != null) {
            mPeerConnectionService.setAudioDirection(peerConnectionId, RtpTransceiverDirection.RECV_ONLY);
        }

        final boolean sendVideo = callConnection.getCall().isVideoSourceOn();
        final EglBase.Context eglContext = sendVideo && mPeerConnectionService != null ? mPeerConnectionService.getEGLContext() : null;
        callConnection.onCreateIncomingPeerConnection(peerConnectionId, eglContext);

        if (call.getTransferDirection() == TO_BROWSER && Boolean.TRUE.equals(callConnection.isTransfer())) {
            call.sendPrepareTransfer();
        }

        sendMessage(MESSAGE_CREATE_INCOMING_CALL);
    }

    private void onCreateOutgoingPeerConnection(@NonNull ErrorCode errorCode,
                                                @NonNull final CallConnection callConnection,
                                                @Nullable final UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateOutgoingPeerConnection errorCode=" + errorCode + " peerConnectionId=" + peerConnectionId);
        }

        final CallState callState = callConnection.getCall();
        if (errorCode == ErrorCode.SUCCESS && peerConnectionId != null) {
            synchronized (this) {
                mPeers.put(peerConnectionId, callConnection);
            }

            final boolean sendVideo = callState.isVideoSourceOn();
            final EglBase.Context eglContext = sendVideo ? getEGLContext() : null;
            callConnection.onCreateOutgoingPeerConnection(peerConnectionId, eglContext);

            Originator originator = callConnection.getOriginator();

            if (FeatureUtils.isTelecomSupported(this) && originator != null) {
                TelecomUtils.addOutgoingTelecomCall(this, originator, callState.getStatus(), callConnection);
            }

        } else {
            callState.remove(callConnection, TerminateReason.fromErrorCode(errorCode));
        }

        sendMessage(MESSAGE_CREATE_OUTGOING_CALL);
    }

    @Override
    public void onAcceptPeerConnection(@NonNull final UUID peerConnectionId, @NonNull Offer offer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAcceptPeerConnection: peerConnectionId=" + peerConnectionId);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection != null) {
            CallService.this.onAcceptPeerConnection(callConnection, peerConnectionId, offer);
        }
    }

    @Override
    public void onChangeConnectionState(@NonNull UUID peerConnectionId, @NonNull PeerConnectionService.ConnectionState state) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onChangeConnectionState: peerConnectionId=" + peerConnectionId);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection != null) {
            CallService.this.onChangeConnectionState(callConnection, state);
        }
    }

    @Override
    public void onTerminatePeerConnection(@NonNull final UUID peerConnectionId, @NonNull final TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminatePeerConnection: peerConnectionId=" + peerConnectionId + " terminateReason=" + terminateReason);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        mPeerConnectionIdTerminated = UUID.fromString(peerConnectionId.toString());

        if (callConnection != null) {
            CallService.this.onTerminatePeerConnection(callConnection, terminateReason);
        }
    }

    @Override
    public void onAddLocalAudioTrack(@NonNull UUID peerConnectionId, @NonNull RtpSender sender, @NonNull AudioTrack audioTrack) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAddLocalAudioTrack: peerConnectionId=" + peerConnectionId +
                    " sender=" + sender + " audioTrack=" + audioTrack.id());
        }
    }

    @Override
    public void onAddRemoteMediaStreamTrack(@NonNull final UUID peerConnectionId, @NonNull final MediaStreamTrack mediaStream) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAddRemoteMediaStreamTrack: peerConnectionId=" + peerConnectionId +
                    " mediaStream=" + mediaStream);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection != null) {
            CallService.this.onAddRemoteMediaStreamTrack(callConnection, mediaStream);
        }
    }

    @Override
    public void onRemoveRemoteTrack(@NonNull UUID peerConnectionId, @NonNull String trackId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRemoveRemoteTrack: peerConnectionId=" + peerConnectionId +
                    " trackId=" + trackId);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection != null) {
            CallService.this.onRemoveRemoteTrack(callConnection, trackId);
        }
    }

    @Override
    public void onPeerHoldCall(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onHoldCall: peerConnectionId=" + peerConnectionId);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null) {
            return;
        }

        callConnection.putOnHold();

        CallParticipantObserver observer = getParticipantObserver();
        if(observer != null){
            uiThreadHandler.post(() -> observer.onEventParticipant(callConnection.getMainParticipant(), CallParticipantEvent.EVENT_HOLD));
        }
    }

    @Override
    public void onPeerResumeCall(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onResumeCall: peerConnectionId=" + peerConnectionId);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null) {
            return;
        }

        callConnection.resume();

        CallParticipantObserver observer = getParticipantObserver();
        if(observer != null){
            uiThreadHandler.post(() -> observer.onEventParticipant(callConnection.getMainParticipant(), CallParticipantEvent.EVENT_RESUME));
        }
    }

    public void onPeerKeyCheckInitiate(@NonNull UUID peerConnectionId, @NonNull Locale locale) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPeerKeyCheckInitiate: peerConnectionId=" + peerConnectionId);
        }

        final CallState call = getActiveCall();
        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null || call == null) {
            return;
        }

        mKeyCheckSessionHandler = new KeyCheckSessionHandler(getApplicationContext(), mTwinmeContext, getParticipantObserver(), call, locale);

        try {
            mExecutor.execute(() -> mKeyCheckSessionHandler.initSession(callConnection));
        } catch (IllegalStateException e) {
            Log.e(LOG_TAG, "Could not initialize key check session", e);
            mKeyCheckSessionHandler = null;
        }
    }

    public void onOnKeyCheckInitiate(@NonNull UUID peerConnectionId, @NonNull ErrorCode errorCode) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOnKeyCheckInitiate: peerConnectionId=" + peerConnectionId + " errorCode=" + errorCode);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null || mKeyCheckSessionHandler == null) {
            return;
        }

        if (errorCode != ErrorCode.SUCCESS) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Peer rejected key check, aborting");
            }
            //TODO: send event to activity?
            mKeyCheckSessionHandler = null;
            return;
        }

        mExecutor.execute(() -> mKeyCheckSessionHandler.onOnKeyCheckInitiate());
    }

    public void onPeerWordCheckResult(@NonNull UUID peerConnectionId, @NonNull WordCheckResult wordCheckResult) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onWordCheck: peerConnectionId=" + peerConnectionId + " wordCheckResult=" + wordCheckResult);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null || mKeyCheckSessionHandler == null) {
            return;
        }

        mExecutor.execute(() -> mKeyCheckSessionHandler.onPeerWordCheckResult(wordCheckResult));
    }

    public void onTerminateKeyCheck(@NonNull UUID peerConnectionId, boolean result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminateKeyCheck: peerConnectionId=" + peerConnectionId + " result=" + result);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null || mKeyCheckSessionHandler == null) {
            return;
        }

        mExecutor.execute(() -> mKeyCheckSessionHandler.onTerminateKeyCheck(result));
    }

    public void onTwincodeURI(@NonNull UUID peerConnectionId, @NonNull String uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwincodeURI: peerConnectionId=" + peerConnectionId + " uri=" + uri);
        }

        final CallConnection callConnection = findPeerConnection(peerConnectionId);
        if (callConnection == null || mKeyCheckSessionHandler == null) {
            return;
        }

        mExecutor.execute(() -> mKeyCheckSessionHandler.onTwincodeUriIQ(uri));
    }

    private void onAcceptPeerConnection(@NonNull CallConnection callConnection, @NonNull UUID peerConnectionId, @NonNull Offer offer) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAcceptPeerConnection: peerConnectionId=" + peerConnectionId + " offer=" + offer);
        }

        CallState call = callConnection.getCall();

        callConnection.setPeerVersion(offer.version);
        callConnection.setTimer(mExecutor, this::callTimeout, CONNECT_TIMEOUT, callConnection.getStatus().toAccepted());

        sendMessage(MESSAGE_ACCEPTED_CALL, call, callConnection);
    }

    private synchronized void onChangeConnectionState(@NonNull CallConnection callConnection,
                                                      @NonNull ConnectionState state) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onChangeConnectionState: state=" + state + ", status= " + callConnection.getStatus() + ", originator: " + callConnection.getOriginator());
        }

        Set<CallConnection> incomingGroupCallConnections = null;

        final CallState call = callConnection.getCall();

        if (Boolean.TRUE.equals(callConnection.isTransfer()) && !call.isTransferReady()) {
            // Still waiting on PrepareTransfer ACKs,
            // we'll call onChangeConnectionState once all ACKs have been received.
            // Ignore other states (i.e. CHECKING), because onChangeConnectionState
            // does nothing if state is not CONNECTED.
            if (state == ConnectionState.CONNECTED) {
                call.setPendingChangeStateConnection(callConnection);
            }
            return;
        }

        if (state == ConnectionState.CONNECTED && call.getTransferDirection() == TO_DEVICE && call.isGroupCall()) {
            // We're transferring the call to this device, and we're now connected with the other participant,
            // so we tell the CallReceiver (browser) it can now leave the call.
            // NB: we check for groupCall to prevent sending the IQ when the initial CallReceiver
            // connection is created.
            //TODO: handle group call (i.e. wait for all participants to connect)?
            call.getInitialConnection().sendTransferDoneIQ();
        }

        final CallState.UpdateState status = call.updateConnectionState(callConnection, state);
        if (status == CallState.UpdateState.FIRST_CONNECTION) {

            if (mAudioManagerDevices != null) {
                // Update the audio mode, we are now connected.
                mAudioManagerDevices.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }

            stopRingtone();
            UUID twincodeOutboundId = null;
            final Originator originator = call.getOriginator();
            if (originator != null) {
                mServiceNotification = mNotificationCenter.createCallNotification(callConnection.getStatus(), originator, call.getId(), call.isAudioSourceOn());
                mCallNotifications.put(call.getId(), mServiceNotification);
                startNotification();
                twincodeOutboundId = originator.getTwincodeOutboundId();
            }

            final DescriptorId descriptorId = call.getCallDescriptorId();
            if (descriptorId != null && twincodeOutboundId != null) {
                mTwinmeContext.getConversationService().acceptCall(newOperation(call, CallOperation.ACCEPTED_CALL), twincodeOutboundId, descriptorId);
            }


            mTwinmeApplication.setInCallInfo(call.getInCallInfo());

            // Call is now accepted => check for other group call connections.
            incomingGroupCallConnections = call.getIncomingGroupCallConnections();

            // CallActivity is already in the foreground => promote CallService to a microphone service.
            if (mActivityStarted.get()) {
                startNotification();
            }

        } else if (status == CallState.UpdateState.FIRST_GROUP) {
            if (mAudioManagerDevices != null) {
                // Update the audio mode, we are now connected with the new peer.
                mAudioManagerDevices.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            stopRingtone();

            if (!callConnection.isInvited() && call.checkOperation(ConnectionOperation.CREATE_CALL_ROOM)) {
                long requestId = newOperation(callConnection, ConnectionOperation.CREATE_CALL_ROOM);
                call.createCallRoom(requestId);
            }

        } else if (status == CallState.UpdateState.NEW_CONNECTION && callConnection.getCallMemberId() == null) {
            if (mAudioManagerDevices != null) {

                // Update the audio mode, we are now connected with the new peer.
                mAudioManagerDevices.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            stopRingtone();

            // This new member is not yet part of the call group, send it an invitation to join.
            if (!callConnection.isInvited() && callConnection.checkOperation(ConnectionOperation.INVITE_CALL_ROOM)) {

                long requestId = newOperation(callConnection, ConnectionOperation.INVITE_CALL_ROOM);
                call.inviteCallRoom(requestId, callConnection);
            }
        }

        sendMessage(MESSAGE_CONNECTION_STATE, call, callConnection);
        // Resume other group click-to-call connections.
        if (incomingGroupCallConnections != null) {
            final Originator originator = call.getOriginator();
            final CallStatus callStatus = call.getStatus().toAccepted();
            for (CallConnection connection : incomingGroupCallConnections) {
                connection.setTimer(mExecutor, this::callTimeout, CONNECT_TIMEOUT, callStatus);
                // We use CallState's originator because we know these connections come from the same group click-to-call link,
                // and we know it can't be null at this stage.
                setOriginator(connection, originator);
                onOperation(callConnection);
            }
        }
    }

    private synchronized void terminateCall(@NonNull CallState call, @NonNull TerminateReason terminateReason, @NonNull CallStatus mode) {
        final Originator originator = call.getOriginator();
        if (CallStatus.isIncoming(mode) && originator != null
                && terminateReason != TerminateReason.DECLINE && terminateReason != TerminateReason.TRANSFER_DONE && terminateReason != TerminateReason.MERGE) {
            mNotificationCenter.missedCallNotification(originator, call.isVideo());
        }

        // If we declined the call, we still have an active incoming call notification.
        NotificationManagerCompat.from(mTwinmeApplication).cancel(CALL_SERVICE_INCOMING_NOTIFICATION_ID);

        final UUID twincodeOutboundId = call.getTwincodeOutboundId();
        final DescriptorId descriptorId = call.getCallDescriptorId();
        if (descriptorId != null && twincodeOutboundId != null) {
            mExecutor.execute(() -> mTwinmeContext.getConversationService().terminateCall(newOperation(call, CallOperation.TERMINATE_CALL), twincodeOutboundId, descriptorId, terminateReason));
        }

        mCallNotifications.remove(call.getId());
        mKeyCheckSessionHandler = null;

        sendMessage(MESSAGE_TERMINATE_CALL, call);

        // Call on hold has terminated.
        if (call == mHoldCall) {
            mHoldCall = null;

            CallState activeCall = mActiveCall;

            if (activeCall != null &&
                    (terminateReason == TerminateReason.CANCEL || terminateReason == TerminateReason.DECLINE || terminateReason == TerminateReason.TIMEOUT)) {
                stopRingtone();

                Notification notification = mCallNotifications.get(activeCall.getId());
                if (notification != null) {
                    mServiceNotification = notification;
                    startNotification();
                }
            }

            return;
        }

        // Switch back to the call on hold if there is one.
        mActiveCall = null;
        if (mHoldCall != null) {
            switchCall();

            return;
        }

        // No other call, we can terminate.
        mTwinmeApplication.setInCallInfo(null);

        // If we are destroyed, don't execute anything: we are done now.
        if (!isAlive()) {

            return;
        }

        mExecutor.execute(() -> {
            stopRingtone();
            if (!call.isConnected() || terminateReason == TerminateReason.TRANSFER_DONE) {
                finish();
            } else {
                // Play the end ringtone on the internal speaker.
                uiThreadHandler.post(() -> {
                    setSpeaker(false, false);
                    mExecutor.execute(() -> startRingtone(RingtoneSoundType.RINGTONE_END));
                });
                if (mShutdownTimer != null) {
                    mShutdownTimer.cancel(false);
                }
                mShutdownTimer = mExecutor.schedule(this::finish, FINISH_TIMEOUT, TimeUnit.SECONDS);
            }
        });
    }

    private synchronized void onTerminatePeerConnection(@NonNull CallConnection callConnection,
                                                        @NonNull TerminateReason terminateReason) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminatePeerConnection: terminateReason=" + terminateReason);
        }

        final CallState call = callConnection.getCall();

        // This contact is revoked, unbind the relation (ignore the REVOKED if this is from a call room member!).
        if (terminateReason == TerminateReason.REVOKED && callConnection.getCallMemberId() == null) {
            final Originator originator = call.getOriginator();
            if (originator instanceof Contact) {
                //Only contacts can be revoked
                mTwinmeContext.unbindContact(BaseService.DEFAULT_REQUEST_ID, null, (Contact) originator);
            }
        }

        mPeers.remove(callConnection.getPeerConnectionId());

        mCallsContacts.remove(call.getOriginatorId());

        // terminateCall() needs the original status to know if the call is incoming.
        // After call.remove() on the last callConnection, the status will always be TERMINATED.
        CallStatus statusBeforeTerminated = call.getStatus();

        if (call.remove(callConnection, terminateReason) || callConnection.getStatus() != CallStatus.TERMINATED) {
            terminateCall(call, terminateReason, statusBeforeTerminated);
        } else {
            stopRingtone();

            sendMessage(MESSAGE_STATE);
        }
    }

    private synchronized void onCreateLocalVideoTrack(@NonNull final VideoTrack videoTrack) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateLocalVideoTrack: videoTrack=" + videoTrack.id());
        }

        final CallState callState = getActiveCall();
        if (callState == null) {
            return;
        }
        final SurfaceViewRenderer localRenderer = callState.getLocalRenderer();
        if (localRenderer != null) {
            try {
                videoTrack.addSink(localRenderer);
                if(mPeerConnectionService != null) {
                    mPeerConnectionService.switchCamera(callState.isFrontCamera(), this::onCameraSwitch);
                }
                sendMessage(MESSAGE_VIDEO_UPDATE);
            } catch (IllegalStateException ex) {
                Log.d(LOG_TAG, "onAddLocalVideoTrack: ", ex);
            }
        } else {
            uiThreadHandler.post(() -> {
                setupLocalVideo(callState);

                // Get and check again the local renderer (it could still be null).
                final SurfaceViewRenderer renderer = callState.getLocalRenderer();
                if (renderer == null) {
                    return;
                }
                try {
                    videoTrack.addSink(renderer);
                    if (mPeerConnectionService != null) {
                        mPeerConnectionService.switchCamera(callState.isFrontCamera(), this::onCameraSwitch);
                    }
                    sendMessage(MESSAGE_VIDEO_UPDATE);
                } catch (IllegalStateException ex) {
                    Log.d(LOG_TAG, "onAddLocalVideoTrack: ", ex);
                }
            });
        }
    }

    private void onAddRemoteMediaStreamTrack(@NonNull final CallConnection callConnection,
                                             @NonNull final MediaStreamTrack mediaStream) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAddRemoteMediaStreamTrack: mediaStream=" + mediaStream);
        }

        if (mediaStream instanceof VideoTrack) {
            uiThreadHandler.post(() -> {
                setupVideo(callConnection.getMainParticipant());
                callConnection.addRemoteMediaStreamTrack(mediaStream);
            });
        } else {
            callConnection.addRemoteMediaStreamTrack(mediaStream);
        }
    }

    private void onRemoveRemoteTrack(@NonNull CallConnection callConnection, @NonNull String trackId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onRemoveRemoteTrack: peerConnection=" + callConnection + " trackId=" + trackId);
        }

        callConnection.removeRemoteTrack(trackId);
    }

    private void onCameraSwitch(@NonNull ErrorCode errorCode, @Nullable Boolean isFrontCamera) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCameraSwitch: isFrontCamera=" + isFrontCamera);
        }

        CallState activeCall = getActiveCall();

        if (activeCall == null || !activeCall.isConnected() || errorCode != ErrorCode.SUCCESS || isFrontCamera == null) {
            return;
        }

        activeCall.setCameraType(isFrontCamera ? CameraType.FRONT : CameraType.BACK);
    }

    private void onDeviceRinging(@NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeviceRinging");
        }

        if (!CallStatus.isActive(callConnection.getCall().getStatus())) {
            startRingtone(RingtoneSoundType.RINGTONE_OUTGOING_CALL_RINGING);
        }

        callConnection.setDeviceRinging();
    }

    /**
     * Get the list of connections.
     *
     * @return the current frozen list of connections.
     */
    @NonNull
    private synchronized List<CallConnection> getConnections() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getConnections");
        }

        // Get a copy of the peer connections because some operations could remove them while we iterate.
        return new ArrayList<>(mPeers.values());
    }

    /**
     * Check if the peer connection is known to us.
     *
     * @param peerConnectionId the peer connection id.
     * @return true if we know this peer connection.
     */
    private synchronized boolean isPeerConnection(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "isPeerConnection peerConnectionId=" + peerConnectionId);
        }

        return mPeers.containsKey(peerConnectionId);
    }

    @Nullable
    private synchronized CallConnection findPeerConnection(@NonNull UUID peerConnectionId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findPeerConnection peerConnectionId=" + peerConnectionId);
        }

        return mPeers.get(peerConnectionId);
    }

    @Nullable
    private synchronized CallState findOriginatorConnection(@NonNull UUID contactId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "findContactConnection contactId=" + contactId);
        }

        return mCallsContacts.get(contactId);
    }

    /**
     * Get the current active audio/video call.
     *
     * @return the active audio/video call.
     */
    @Nullable
    private synchronized CallState getActiveCall() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getActiveCall");
        }

        return mActiveCall;
    }

    @Nullable
    private synchronized CallState getHoldCall() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getHoldCall");
        }

        return mHoldCall;
    }

    /**
     * Switch the active call and the on-hold call.
     */
    private synchronized void switchCall() {
        if (DEBUG) {
            Log.d(LOG_TAG, "switchCall");
        }

        final CallState call = mActiveCall;
        final CallState hold = mHoldCall;
        if (hold == null) {

            return;
        }

        // Must run stopShareLocation from the main UI thread.
        if (mLocationManager.isLocationShared()) {
            uiThreadHandler.post(() -> stopShareLocation(true));
        }

        if (call != null) {
            holdCall(call);
        }

        resumeCall(hold);

        mServiceNotification = mCallNotifications.get(hold.getId());
        if(mServiceNotification != null) {
            startNotification();
        }

        mActiveCall = hold;
        mHoldCall = call;

        sendMessage(MESSAGE_STATE);
    }

    private void holdCall(@NonNull CallState call) {
        call.putOnHold();

        sendMessage(MESSAGE_CALL_ON_HOLD, call);
    }

    private void resumeCall(@NonNull CallState call) {
        call.resume();

        mAudioMute = !call.isAudioSourceOn();
        mIsCameraMute = !call.isVideoSourceOn();
        mTwinmeApplication.setInCallInfo(call.getInCallInfo());

        sendMessage(MESSAGE_CALL_RESUMED, call);
    }

    private void startKeyCheck(@Nullable Locale language) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startKeyCheck: language=" + language);
        }

        CallState call = getActiveCall();
        if (call == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "No active call, aborting");
            }
            return;
        }

        if (language == null) {
            language = Locale.getDefault();
        }

        mKeyCheckSessionHandler = new KeyCheckSessionHandler(getApplicationContext(), mTwinmeContext, getParticipantObserver(), call, language);

        try {
            mExecutor.execute(mKeyCheckSessionHandler::initSession);
        } catch (IllegalStateException e) {
            Log.e(LOG_TAG, "Error initializing key check session", e);
            mKeyCheckSessionHandler = null;
        }
    }

    private void stopKeyCheck() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopKeyCheck");
        }

        mKeyCheckSessionHandler = null;
    }

    @Nullable
    private CallStatus getMode() {

        final CallState call = getActiveCall();
        return call != null ? call.getStatus() : null;
    }

    private void setOriginator(@NonNull CallConnection callConnection, @NonNull Originator originator) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setOriginator callConnection=" + callConnection);
        }

        Bitmap avatar = null;
        ImageId avatarId = originator.getAvatarId();
        if (avatarId != null) {
            avatar = mTwinmeContext.getImageService().getImage(avatarId, ImageService.Kind.THUMBNAIL);
        }

        // Also get the identity avatar so that we can send it to group members.
        Bitmap identityAvatar = null;
        avatarId = originator.getIdentityAvatarId();
        if (avatarId != null) {
            identityAvatar = mTwinmeContext.getImageService().getImage(avatarId, ImageService.Kind.THUMBNAIL);
        }

        Bitmap groupAvatar = null;

        if (originator.getType() == Originator.Type.GROUP_MEMBER) {
            // Also get the group avatar so that we can display it where appropriate.
            avatarId = ((GroupMember) originator).getGroup().getAvatarId();
            if (avatarId != null) {
                groupAvatar = mTwinmeContext.getImageService().getImage(avatarId, ImageService.Kind.THUMBNAIL);
            }
        }

        // Set the call's originator if there is none.
        if (callConnection.getOriginator() == null) {
            callConnection.getCall().setOriginator(originator, avatar, identityAvatar, groupAvatar);
        }
        callConnection.setOriginator(originator, avatar, identityAvatar, groupAvatar);
    }

    private void setupAudio() {
        if (DEBUG) {
            Log.d(LOG_TAG, "setupAudio");
        }

        if (mAudioManagerDevices != null) {
            if (!mAudioManagerDevices.isRunning()) {
                mAudioManagerDevices.start(this, androidx.media.session.MediaButtonReceiver.class);
            }

                final CallState call = getActiveCall();

                // Do nothing if this call is already connected (we are adding a new participant).
                if (call == null || !call.isConnected()) {

                    mAudioManagerDevices.setMicrophoneMute(false);
                    // Set the speaker from the main thread (after sendMessage())
                    TwinmeAudioManager audioManager = mAudioManagerDevices;
                    if (audioManager != null) {
                        uiThreadHandler.post(() -> audioManager.initDevice(call != null && call.isVideo()));
                    }
                }
        }
    }

    /**
     * Create the EGL base context if it does not exist.  It can be called from any thread before we call the
     * PeerConnectionService.initSources().
     */
    @Nullable
    private EglBase.Context getEGLContext() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getEGLContext");
        }

        return mPeerConnectionService != null ? mPeerConnectionService.getEGLContext() : null;
    }

    private void setupLocalVideo(@NonNull CallState call) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setupLocalVideo call=" + call);
        }

        ThreadUtils.checkIsOnMainThread();

        final EglBase.Context eglContext = getEGLContext();
        if (eglContext == null) {
            return;
        }

        if (call.getLocalRenderer() == null) {
            SurfaceViewRenderer localRenderer = new SurfaceViewRenderer(getUiContext());

            try {
                localRenderer.init(eglContext, null);
                localRenderer.setFpsReduction(30.0f);
                call.setLocalRenderer(localRenderer);
            } catch (RuntimeException ex) {
                // Some devices may raise a RuntimeException with "Failed to create EGL context".
                // Avoid a crash and report a camera error.
                sendError(ErrorType.CAMERA_ERROR, call);
            }
        }
    }

    private void setupVideo(@Nullable CallParticipant participant) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setupVideo participant=" + participant);
        }

        ThreadUtils.checkIsOnMainThread();

        if (participant == null) {
            return;
        }

        final EglBase.Context eglContext = getEGLContext();
        if (eglContext == null) {
            return;
        }

        if (!participant.setupVideo(getUiContext(), eglContext)) {
            // Some devices may raise a RuntimeException with "Failed to create EGL context".
            // Avoid a crash and report a camera error.
            sendError(ErrorType.CAMERA_ERROR, participant.getCallConnection().getCall());
        }
    }

    private void setSpeaker(boolean audioSpeaker, boolean notify) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setSpeaker: speaker=" + audioSpeaker + " notify=" + notify);
        }

        final TwinmeAudioManager audioManager = mAudioManagerDevices;

        // Switch to the speaker or select the default device when speaker is off.
        if ((getActiveCall() == null || !getActiveCall().isTelecomSupported()) && audioManager != null) {
            uiThreadHandler.post(() -> {
                audioManager.setSpeaker(audioSpeaker);
                if (notify) {
                    // Send a speaker update so that the UI is up to date.
                    sendMessage(MESSAGE_AUDIO_SINK_UPDATE);
                }
            });
        } else {
            CallAudioManager audioMgr = getAudioManager();
            if (audioMgr != null) {
                if (audioMgr.getAudioDevices().contains(AudioDevice.BLUETOOTH) || audioMgr.getAudioDevices().contains(AudioDevice.WIRED_HEADSET)) {
                    // We assume setSpeaker() is called only when the only available routes are EARPIECE and
                    // SPEAKER. CallAudioManager.setCommunicationDevice() should be used if, for example,
                    // we want to switch from SPEAKER back to BLUETOOTH.
                    // I guess we should add setSpeaker() to CallAudioManager, but it seems Telecom doesn't
                    // provide a simple way to revert to the default route, and since we already have the
                    // required logic in CallActivity the added complexity might not be worth it.
                    Log.w(LOG_TAG, "Use CallAudioManager.setCommunicationDevice() to switch to/from the speaker when bluetooth or wired audio devices are available");
                }
                audioMgr.setCommunicationDevice(audioSpeaker ? AudioDevice.SPEAKER_PHONE : AudioDevice.EARPIECE);
            }
        }
    }

    //
    // Audio Devices (Bluetooth)
    //

    /**
     * Called when a media button is pressed.
     *
     * @param keyEvent the media button being pressed.
     */
    @Override
    public void onMediaButton(@NonNull KeyEvent keyEvent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onMediaButton keyEvent=" + keyEvent);
        }

        final CallStatus mode = getMode();
        if (mode == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }

        switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (CallStatus.isIncoming(mode)) {
                    final Intent serviceIntent = new Intent(this, CallService.class);
                    serviceIntent.setAction(CallService.ACTION_ACCEPT_CALL);
                    onActionAcceptCall(serviceIntent);
                } else if (CallStatus.isActive(mode)) {
                    final Intent serviceIntent = new Intent(this, CallService.class);
                    serviceIntent.setAction(CallService.ACTION_TERMINATE_CALL);
                    serviceIntent.putExtra(CallService.PARAM_TERMINATE_REASON, TerminateReason.SUCCESS);
                    onActionTerminateCall(serviceIntent);
                }
                break;
            default:
                break;
        }
    }

    private void onCallAudioStateChanged(@NonNull Intent intent) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCallAudioStateChanged: " + intent);
        }
        sendMessage(MESSAGE_AUDIO_SINK_UPDATE);
    }

    @Override
    public void onAudioDeviceChanged(@NonNull final AudioDevice device, @NonNull final Set<AudioDevice> availableDevices) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onAudioDeviceChanged: " + availableDevices + ", " + "selected: " + device);
        }
        sendMessage(MESSAGE_AUDIO_SINK_UPDATE);
    }

    private synchronized boolean isAlive() {

        return this == sCurrent;
    }

    private void onError(@Nullable final CallConnection callConnection, int operationId,
                         ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        // The CallService has finished and may be stopping, ignore pending errors that could occur due to concurrency.
        if (callConnection != null && callConnection.getStatus() == CallStatus.TERMINATED) {

            return;
        }

        // Wait for reconnection
        if (errorCode == ErrorCode.TWINLIFE_OFFLINE) {

            return;
        }

        final CallState call = callConnection != null ? callConnection.getCall() : null;

        if (errorCode == ErrorCode.ITEM_NOT_FOUND) {
            switch (operationId) {
                case CallOperation.START_CALL:
                    if (call != null) {
                        call.checkOperation(CallOperation.START_CALL_DONE);
                    }
                    return;

                case CallOperation.ACCEPTED_CALL:
                    if (call != null) {
                        call.checkOperation(CallOperation.ACCEPTED_CALL_DONE);
                    }
                    return;

                case CallOperation.TERMINATE_CALL:
                    if (call != null) {
                        call.checkOperation(CallOperation.TERMINATE_CALL_DONE);
                    }
                    return;

                case ConnectionOperation.GET_CONTACT:
                    sendError(ErrorType.CONTACT_NOT_FOUND, call);
                    break;

                case ConnectionOperation.CREATE_OUTGOING_PEER_CONNECTION:
                case ConnectionOperation.CREATE_INCOMING_PEER_CONNECTION:
                    sendError(ErrorType.CONNECTION_NOT_FOUND, call);
                    break;
            }
            if (callConnection != null) {
                callConnection.terminate(TerminateReason.GONE);
                onTerminatePeerConnection(callConnection, TerminateReason.GONE);
            }

            // The error can be reported after we are destroy: don't schedule finish if we are already dead.
            if (isAlive()) {
                mExecutor.execute(this::finish);
            }
            return;
        }

        if (errorCode == ErrorCode.WEBRTC_ERROR) {

            sendError(ErrorType.CAMERA_ERROR, call);
            return;
        }

        if (isAlive()) {
            mTwinmeContext.assertion(CallAssertPoint.ON_ERROR, AssertPoint.create(call.getOriginator())
                            .putOperationId(operationId).put(errorCode));
            sendError(ErrorType.INTERNAL_ERROR, call);
            mExecutor.execute(this::finish);
        }
    }

    private void callTimeout(@NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "callTimeout callConnection=" + callConnection);
        }

        if (callConnection.getConnectionState() == ConnectionState.CONNECTED) {
            if(DEBUG) {
                Log.d(LOG_TAG, "We're actually connected! Aborting terminate.");
            }
            return;
        }

        callConnection.terminate(TerminateReason.TIMEOUT);

        onTerminatePeerConnection(callConnection, TerminateReason.TIMEOUT);
    }

    private void startRingtone(@NonNull RingtoneSoundType type) {
        if (DEBUG) {
            Log.d(LOG_TAG, "startRingtone type=" + type);
        }

        CallStatus mode;
        TwinmeAudioManager audioManager;
        synchronized (this) {
            mode = getMode();
            audioManager = mAudioManagerDevices;
        }

        if (audioManager != null) {
            uiThreadHandler.post(() -> audioManager.startRingtone(type, mode));
        }
    }

    @Override
    public void onPlayerReady() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPlayerReady");
        }
    }

    private void stopRingtone() {
        if (DEBUG) {
            Log.d(LOG_TAG, "stopRingtone");
        }

        TwinmeAudioManager audioManager;
        synchronized (this) {
            audioManager = mAudioManagerDevices;
        }

        if (audioManager == null) {
            return;
        }

        uiThreadHandler.post(audioManager::stopRingtone);
    }

    private void sendError(@NonNull ErrorType status, @Nullable CallState call) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendError: status=" + status);
        }

        Intent intent = new Intent(Intents.INTENT_CALL_SERVICE_MESSAGE);
        intent.setPackage(getPackageName());
        intent.putExtra(CALL_SERVICE_EVENT, MESSAGE_ERROR);
        intent.putExtra(CALL_ERROR_STATUS, status);
        if (call != null) {
            call.putState(intent);
            intent.putExtra(CALL_IS_DOUBLE_CALL, mHoldCall != null);
            intent.putExtra(CALL_IS_HOLD_CALL, call == mHoldCall);
        }
        sendBroadcast(intent);
    }

    void sendMessage(@NonNull String event) {
        sendMessage(event, getActiveCall());
    }

    void sendMessage(@NonNull String event, @Nullable CallState call) {
        sendMessage(event, call, null);
    }

    void sendMessage(@NonNull String event, @Nullable CallState call, @Nullable CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendMessage: event=" + event);
        }

        Intent intent = new Intent(Intents.INTENT_CALL_SERVICE_MESSAGE);
        intent.setPackage(getApplicationContext().getPackageName());
        intent.putExtra(CALL_SERVICE_EVENT, event);

        CallAudioManager audioManager = getAudioManager();

        if (audioManager != null) {
            intent.putExtra(CALL_SELECTED_AUDIO_SINK, audioManager.getSelectedAudioDevice());
            intent.putExtra(CALL_AUDIO_DEVICE_NAME, audioManager.getBluetoothDeviceName());
        }

        if (call != null) {
            call.putState(intent);
            intent.putExtra(CALL_CAMERA_MUTE_STATE, mIsCameraMute || !call.isVideo());
            intent.putExtra(CALL_MUTE_STATE, !call.isAudioSourceOn());
            intent.putExtra(CALL_HAS_CAMERA, call.getLocalRenderer() != null);
            intent.putExtra(CALL_IS_DOUBLE_CALL, mHoldCall != null);
            intent.putExtra(CALL_IS_HOLD_CALL, call == mHoldCall);
        } else {
            intent.putExtra(CALL_MUTE_STATE, mAudioMute);
            intent.putExtra(CALL_HAS_CAMERA, false);
        }

        if (callConnection != null) {
            intent.putExtra(CALL_SERVICE_PEER_CONNECTION_ID, callConnection.getPeerConnectionId());
        }

        sendBroadcast(intent);
    }

    synchronized int allocateParticipantId(){
        return mParticipantCounter++;
    }

    private synchronized void finish() {
        if (DEBUG) {
            Log.d(LOG_TAG, "finish " + mStartId);
        }

        stopSelf(mStartId);
    }
}
