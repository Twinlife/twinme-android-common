/*
 *  Copyright (c) 2022-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 */

package org.twinlife.twinme.calls;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.ConversationService.GeolocationDescriptor;
import org.twinlife.twinme.calls.streaming.StreamPlayer;
import org.twinlife.twinme.calls.streaming.StreamingStatus;
import org.twinlife.twinme.models.Zoomable;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.ThreadUtils;

import java.util.UUID;

/**
 * A participant in an Audio or Video call.
 *
 * To support group calls in different architectures, a CallParticipant is separated from the CallConnection.
 *
 * We have to be careful that a participant can be one of our contact (in which case we know it) but it
 * can be a user that is not part of our contact list.  In that case, the name and avatar are not directly
 * known and they are provided by other means.
 *
 * The participant has:
 *
 * - a name, a description, an avatar,
 * - a SurfaceViewRenderer when the video is active and we receive the participant video stream,
 * - a set of audio/video status information
 */
public final class CallParticipant implements RendererCommon.RendererEvents {
    private static final String LOG_TAG = "CallParticipant";
    private static final boolean DEBUG = false;

    public interface CallParticipantListener {

        void onFrameResolutionChanged(@NonNull CallParticipant participant);
    }

    private final int mParticipantId;

    /**
     * If not null, indicates that this participant is the transfer target of the participant
     * referenced by this ID.
     */
    @Nullable
    private Integer mTransferredFromParticipantId;

    /**
     * If not null, indicates that this participant has been transferred to the participant
     * referenced by this ID.
     */
    @Nullable
    private Integer mTransferredToParticipantId;

    @NonNull
    private final CallConnection mConnection;

    @Nullable
    private volatile Bitmap mAvatar;

    @Nullable
    private volatile Bitmap mGroupAvatar;

    @Nullable
    private volatile String mName;

    @Nullable
    private volatile String mDescription;

    @Nullable
    private volatile UUID mSenderId;

    @Nullable
    private volatile SurfaceViewRenderer mRemoteRenderer;

    private volatile boolean mAudioMute;
    private volatile boolean mCameraMute;
    private volatile boolean mIsScreenSharing;

    private volatile int mVideoWidth;
    private volatile int mVideoHeight;
    private volatile int mRemoteCameraBitmap;
    private volatile int mRemoteActiveCamera;

    @Nullable
    private CallParticipantListener mCallParticipantListener;

    /**
     * Get the call connection associated with this participant.
     *
     * @return the call connection.
     */
    @NonNull
    public CallConnection getCallConnection() {

        return mConnection;
    }

    /**
     * Check if this participant supports P2P group calls.
     *
     * @return NULL if we don't know, TRUE if P2P group calls are supported.
     */
    @Nullable
    public Boolean isGroupSupported() {

        return mConnection.isGroupSupported();
    }

    /**
     * Check if this participant supports message
     *
     * @return NULL if we don't know, TRUE if message are supported.
     */
    @Nullable
    public Boolean isMessageSupported() {

        return mConnection.isMessageSupported();
    }

    /**
     * Check if this connection supports the sending geolocation during a call.
     *
     * @return NULL if we don't know, TRUE if sending geolocation is supported.
     */
    @Nullable
    public Boolean isGeolocSupported() {

        return mConnection.isGeolocSupported();
    }

    /**
     * Indicates whether we can take control of the peer camera and zoom on it remotely.
     * @return the zoomable status for this participant.
     */
    @NonNull
    public Zoomable isZoomable() {

        return mConnection.isZoomable();
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

        return mConnection.getStreamingStatus();
    }

    /**
     * Get the stream player that is playing media streams sent by the peer.
     *
     * @return the stream player or null.
     */
    @Nullable
    public StreamPlayer getStreamPlayer() {

        return mConnection.getStreamPlayer();
    }

    /**
     * Get the remote renderer for this peer connection.
     *
     * @return the remote renderer or null if this peer connection has no video.
     */
    @Nullable
    public SurfaceViewRenderer getRemoteRenderer() {

        return mRemoteRenderer;
    }

    /**
     * Returns true if the peer has the audio muted.
     *
     * @return true if the peer has the audio muted.
     */
    public boolean isAudioMute() {

        return mAudioMute;
    }

    /**
     * Returns true if the peer has the video muted.
     *
     * @return true if the peer has the video muted.
     */
    public boolean isCameraMute() {

        return mCameraMute;
    }

    /**
     * Returns true if the peer is sharing a screen or a window.
     *
     * @return true if the peer is sharing a screen or a window.
     */
    public boolean isScreenSharing() {

        return mIsScreenSharing;
    }

    /**
     * Get the participant name (it could come from the Contact but also provided by other means for group calls).
     *
     * @return the participant name.
     */
    @Nullable
    public String getName() {

        return mName;
    }

    /**
     * Get the participant description (it could come from the Contact but also provided by other means for group calls).
     *
     * @return the participant description.
     */
    @Nullable
    public String getDescription() {

        return mDescription;
    }

    /**
     * Get the participant avatar (it could come from the Contact but also provided by other means for group calls).
     *
     * @return the participant avatar.
     */
    @Nullable
    public Bitmap getAvatar() {

        return mAvatar;
    }

    /**
     * Get the participant's group avatar.
     *
     * @return the participant's group avatar if the participant represents a group member, null otherwise.
     */
    @Nullable
    public Bitmap getGroupAvatar() {

        return mGroupAvatar;
    }

    /**
     * Get a unique id that identifies this participant for the duration of the call.
     *
     * @return the participant id.
     */
    public int getParticipantId() {

        return mParticipantId;
    }

    /**
     * Get the UUID that this participant is using to emit messages during the call.
     *
     * @return the participant sender id or null if no message was sent.
     */
    @Nullable
    public UUID getSenderId() {

        return mSenderId;
    }

    /**
     * Get remote video width
     *
     * @return video width
     */
    public int getVideoWidth() {

        return mVideoWidth;
    }

    /**
     * Get remote video height
     *
     * @return video height
     */
    public int getVideoHeight() {

        return mVideoHeight;
    }

    /**
     * Get the current peer geolocation descriptor.
     *
     * @return the current peer geolocation descriptor or null.
     */
    @Nullable
    public GeolocationDescriptor getCurrentGeolocation() {

        return mConnection.getCurrentGeolocation();
    }

    /**
     * Get the peer connection id associated with this participant.
     *
     * @return the peer connection id or null.
     */
    @Nullable
    public UUID getPeerConnectionId() {

        return mConnection.getPeerConnectionId();
    }

    @NonNull
    public CallStatus getStatus() {

        return mConnection.getStatus();
    }

    public int getRemoteActiveCamera() {

        return mRemoteActiveCamera;
    }

    /**
     * Ask the peer to get the control of its camera.  The response will come asynchronously
     * in a EVENT_CAMERA_CONTROL_DENIED or EVENT_CAMERA_CONTROL_GRANTED event.
     */
    public void remoteAskControl() {

        mConnection.sendCameraControl(CameraControlIQ.Mode.CHECK, 0, 0);
    }

    /**
     * Answer to GRANT/DENY access to our camera to the peer.
     * @param grant true if we grant access to our camera
     */
    public void remoteAnswerControl(boolean grant) {

        if (grant) {
            mConnection.sendCameraGrant();
        } else {
            mConnection.sendCameraResponse(ErrorCode.NO_PERMISSION, 0, 0, 0, 0);
        }
    }

    /**
     * Stop controlling the peer camera.
     */
    public void remoteStopControl() {

        // The STOP can be sent by both participants.
        mConnection.sendCameraStop();
    }

    /**
     * Zoom on the peer camera.
     * @param progress the zoom progress in range 0..100
     */
    public void remoteSetZoom(int progress) {

        if (mRemoteCameraBitmap != 0) {
            mConnection.sendCameraControl(CameraControlIQ.Mode.ZOOM, 0, progress);
        }
    }

    /**
     * Switch the peer camera to the front or back camera if we are allowed.
     * @param front true to select the front camera.
     */
    public void remoteSwitchCamera(boolean front) {

        if (mRemoteCameraBitmap != 0) {
            mConnection.sendCameraControl(CameraControlIQ.Mode.SELECT, front ? 1 : 2, 0);
        }
    }

    /**
     * Turn ON/OFF the camera of the peer if we are allowed to
     * @param mute true to turn OFF (mute) the peer camera.
     */
    public void remoteCameraMute(boolean mute) {

        if (mRemoteCameraBitmap != 0) {
            mConnection.sendCameraControl(mute ? CameraControlIQ.Mode.OFF : CameraControlIQ.Mode.ON, 0, 0);
        }
    }

    /*
     * Package private methods.
     */

    /**
     * Create a call participant for the given peer connection.
     *
     * @param callConnection the peer call connection.
     * @param participantId the unique participant id.
     */
    CallParticipant(@NonNull CallConnection callConnection, int participantId) {

        mConnection = callConnection;
        mAvatar = null;
        mName = null;
        mDescription = null;
        mAudioMute = true;
        mCameraMute = true;
        mVideoWidth = 0;
        mVideoHeight = 0;
        mParticipantId = participantId;
        mTransferredFromParticipantId = null;
        mTransferredToParticipantId = null;
        mRemoteCameraBitmap = 0; // Remote camera control is disabled until the peer acknowledge.
        mRemoteActiveCamera = 0;
    }

    void setMicrophoneMute(boolean mute) {

        mAudioMute = mute;
    }

    void setCameraMute(boolean mute) {

        mCameraMute = mute;
    }

    void setScreenSharing(boolean state) {

        mIsScreenSharing = state;
    }

    void setInformation(@Nullable String name, @Nullable String description, @Nullable Bitmap avatar, @Nullable Bitmap groupAvatar) {

        mName = name;
        mDescription = description;
        mAvatar = avatar;
        mGroupAvatar = groupAvatar;
    }

    void updateSender(@NonNull UUID senderId) {

        if (mSenderId == null) {
            mSenderId = senderId;
        }
    }

    void setRemoteControl(int allowedCamera, int activeCamera) {

        mRemoteCameraBitmap = allowedCamera;
        mRemoteActiveCamera = activeCamera;
    }

    @Nullable
    public Integer getTransferredFromParticipantId() {
        return mTransferredFromParticipantId;
    }

    public void setTransferredFromParticipantId(@Nullable Integer transferredParticipantId) {
        this.mTransferredFromParticipantId = transferredParticipantId;
    }

    @Nullable
    public Integer getTransferredToParticipantId() {
        return mTransferredToParticipantId;
    }

    public void setTransferredToParticipantId(@Nullable Integer transferredToParticipantId) {
        this.mTransferredToParticipantId = transferredToParticipantId;
    }

    public void setCallParticipantListener(CallParticipantListener callParticipantListener) {

        mCallParticipantListener = callParticipantListener;
    }

    public void transfer(@NonNull CallParticipant transferredParticipant) {
            setInformation(transferredParticipant.getName(), transferredParticipant.getDescription(), transferredParticipant.getAvatar(), transferredParticipant.getGroupAvatar());
            setTransferredFromParticipantId(transferredParticipant.getParticipantId());
            transferredParticipant.setTransferredToParticipantId(getParticipantId());
    }

    boolean setupVideo(@NonNull Context context, @NonNull EglBase.Context eglBaseContext) {
        if (DEBUG) {
            Log.d(LOG_TAG, "setupVideo");
        }

        if (mRemoteRenderer == null) {
            ThreadUtils.checkIsOnMainThread();

            SurfaceViewRenderer remoteRenderer = new SurfaceViewRenderer(context);
            try {
                remoteRenderer.init(eglBaseContext, this);
                remoteRenderer.setFpsReduction(30.0f);
            } catch (RuntimeException ex) {
                // Some devices may raise a RuntimeException with "Failed to create EGL context".
                // Avoid a crash and report a camera error.
                // sendError(ErrorType.CAMERA_ERROR);
                return false;
            }

            mRemoteRenderer = remoteRenderer;
        }

        return true;
    }

    /**
     * Release the remote renderer when the connexion is destroyed.
     */
    void release() {
        if (DEBUG) {
            Log.d(LOG_TAG, "release");
        }

        // Release the surface renderer from the main UI thread.
        final SurfaceViewRenderer remoteRenderer = mRemoteRenderer;
        if (remoteRenderer != null) {
            mRemoteRenderer = null;

            Handler handler = mConnection.getCall().getHandler();
            handler.post(() -> {
                ViewParent viewParent = remoteRenderer.getParent();
                if (viewParent != null) {
                    ((ViewGroup) viewParent).removeView(remoteRenderer);
                }

                remoteRenderer.release();
            });
        }
    }

    @Override
    public void onFirstFrameRendered() {

    }

    @Override
    public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {

        if (rotation == 90 || rotation == 270) {
            //noinspection SuspiciousNameCombination
            mVideoWidth = videoHeight;
            mVideoHeight = videoWidth;
        } else {
            mVideoWidth = videoWidth;
            mVideoHeight = videoHeight;
        }

        if (mCallParticipantListener != null) {
            mCallParticipantListener.onFrameResolutionChanged(this);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "CallParticipant[name="+mName+"]";
    }
}
