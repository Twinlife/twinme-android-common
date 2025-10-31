/*
 *  Copyright (c) 2014-2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Christian Jacquemot (Christian.Jacquemot@twinlife-systems.com)
 *   Stephane Carrez (Stephane.Carrez@twin.life)
 *   Fabrice Trescartes (Fabrice.Trescartes@twin.life)
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.ConnectionStatus;
import org.twinlife.twinlife.DisplayCallsMode;
import org.twinlife.twinlife.JobService;
import org.twinlife.twinme.NotificationCenter;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.skin.DisplayMode;
import org.twinlife.twinme.skin.EmojiSize;
import org.twinlife.twinme.skin.FontSize;
import org.twinlife.twinme.utils.AppStateInfo;
import org.twinlife.twinme.utils.InCallInfo;
import org.twinlife.twinme.utils.coachmark.CoachMark;
import org.twinlife.twinme.utils.update.LastVersion;

import java.io.File;
import java.util.Date;
import java.util.UUID;

@SuppressWarnings("unused")
public interface TwinmeApplication extends org.twinlife.twinme.TwinmeApplication {

    SharedPreferences getSharedPreferences(String updateScorePreferences, int modePrivate);

    File getCacheDir();

    File getFilesDir();

    void checkLastVersion(LastVersion mLastVersion);

    State getState();

    //
    // Space description
    //
    default boolean showSpaceDescription(){
        return false;
    }

    default void hideSpaceDescription(){
        //NOOP, implemented in Skred branch
    }

    enum RingtoneType {

        AUDIO_RINGTONE, VIDEO_RINGTONE, NOTIFICATION_RINGTONE
    }

    enum State {
        STARTING,
        UPGRADING,
        READY,
        MIGRATION
    }

    enum DefaultTab {
        PROFILES,
        SPACES,
        CALLS,
        CONTACTS,
        CONVERSATIONS,
        NOTIFICATIONS
    }

    enum HapticFeedbackMode {
        SYSTEM,
        ON,
        OFF
    }

    enum SendImageSize {
        SMALL,
        MEDIUM,
        ORIGINAL
    }

    enum SendVideoSize {
        LOWER,
        ORIGINAL
    }

    enum OnboardingType {
        CERTIFIED_RELATION,
        EXTERNAL_CALL,
        PROFILE,
        SPACE,
        TRANSFER,
        ENTER_MINI_CODE,
        MINI_CODE,
        REMOTE_CAMERA,
        REMOTE_CAMERA_SETTING,
        TRANSFER_CALL,
        PROXY
    }


    @NonNull
    String errorToString(BaseService.ErrorCode errorCode);

    void onError(@NonNull Activity activity, BaseService.ErrorCode errorCode, @Nullable String message, @Nullable Runnable errorCallback);

    //
    // Personalization management
    //

    boolean showWelcomeScreen();

    void hideWelcomeScreen();

    void restoreWelcomeScreen();

    default void setFirstInstallation(){
        //NOOP, implemented in Twinme
    }

    default void setFirstInstallationTime() {
        //NOOP, implemented in Twinme
    }

    default boolean showUpgradeScreen(){
        return false;
    }

    default boolean doNotShowUpgradeScreen(){
        return true;
    }

    default void setDoNotShowUpgradeScreen() {
        //NOOP, implemented in Twinme
    }

    default boolean canShowUpgradeScreen() {
        return false;
    }

    default void setCanShowUpgradeScreen() {
        //NOOP, implemented in Twinme
    }

    int fontSize();

    void updateFontSize(FontSize fontSize);

    int emojiFontSize();

    boolean visualizationLink();

    void updateEmojiFontSize(EmojiSize emojiSize);

    int displayMode();

    void updateDisplayMode(DisplayMode displayMode);

    int hapticFeedbackMode();

    void updateHapticFeedbackMode(HapticFeedbackMode hapticFeedbackMode);

    int defaultTab();

    void updateDefaultTab(DefaultTab defaultTab);

    DisplayCallsMode displayCallsMode();

    void setDisplayCallsMode(DisplayCallsMode displayCallsMode);

    int updateProfileMode();

    void setUpdateProfileMode(Profile.UpdateMode updateProfileMode);

    @Nullable
    Uri getRingtone(RingtoneType ringtoneType);

    boolean getVibration(RingtoneType ringtoneType);

    void resetPreferences();

    //
    // Grant copy permissions to contacts
    //

    boolean messageCopyAllowed();

    boolean imageCopyAllowed();

    boolean audioCopyAllowed();

    boolean videoCopyAllowed();

    boolean fileCopyAllowed();

    boolean getDisplayNotificationSender();

    boolean getDisplayNotificationLike();

    String defaultDirectoryToExportFiles();

    String defaultDirectoryToSaveFiles();

    Uri defaultUriToSaveFiles();

    int sendImageSize();

    void setSendImageSize(SendImageSize sendImageSize);

    int sendVideoSize();

    void setSendVideoSize(SendVideoSize sendVideoSize);

    //
    // Privacy Management
    //

    @Override
    default boolean screenLocked(){
        return false;
    }

    default void setScreenLocked(boolean lock){
        //NOOP, implemented in Twinme+
    }

    default int screenLockTimeout(){
        return 0;
    }

    default void updateScreenLockTimeout(int time) {
        //NOOP, implemented in Twinme+
    }

    default boolean lastScreenHidden(){
        return false;
    }

    default void setLastScreenHidden(boolean hide){
        //NOOP, implemented in Twinme+
    }

    default boolean showLockScreen(){
        return false;
    }

    default void unlockScreen(){
        //NOOP, implemented in Twinme+
    }

    default void setAppBackgroundDate(Date date){
        //NOOP, implemented in Twinme+
    }

    //
    //  Call Management
    //

    InCallInfo inCallInfo();

    void setInCallInfo(InCallInfo inCallInfo);

    boolean isVideoInFitMode();

    void setVideoInFitMode(boolean fitMode);

    boolean askCallQualityWithCallDuration(long duration);

    //
    //  App Info Management
    //

    @Nullable
    AppStateInfo appInfo();

    void setAppInfo(@Nullable AppStateInfo appInfo);

    void onConnectionStatusChange(@NonNull ConnectionStatus connectionStatus);

    //
    // Access twinme management
    //

    default int ephemeralExpireTimeout(){
        //Skred-specific
        return 0;
    }

    boolean showConnectedMessage();

    void setShowConnectedMessage(boolean show);

    //
    // First installation management
    //

    default boolean isFirstInstallation(){
        return false;
    }

    default void setFirstInstallation(boolean first) {
        //NOOP, implemented in Twinme+
    }

    //
    // Last version
    //
    @Nullable
    LastVersion getLastVersion();

    boolean hasNewVersion();

    //
    // Invitation subscription
    //
    default String getInvitationSubscriptionImage(){
        //Skred-specific
        return "";
    }

    default void setInvitationSubscriptionImage(String image){
        //Skred-specific
    }

    default String getInvitationSubscriptionTwincode(){
        //Skred-specific
        return "";
    }

    default void setInvitationSubscriptionTwincode(String twincode){
        //Skred-specific
    }

    //
    // Coach Mark
    //
    boolean showCoachMark();

    void setShowCoachMark(boolean showCoachMark);

    boolean showCoachMark(CoachMark.CoachMarkTag coachMarkTag);

    void hideCoachMark(CoachMark.CoachMarkTag coachMarkTag);

    //
    // current space
    //
    default boolean isCurrentSpace(UUID spaceId){
        return false;
    }

    default boolean hasCurrentSpace(){
        return false;
    }

    default Space getCurrentSpace(){
        return null;
    }

    //
    // background
    //
    boolean isInBackground();

    boolean showWhatsNew();

    //
    // Enable notifications
    //

    boolean showEnableNotificationScreen();

    //
    // Group call animation
    //

    boolean showGroupCallAnimation();

    void hideGroupCallAnimation();

    //
    // Onboarding
    //

    boolean startCallRestrictionMessage();

    void setShowCallRestrictionMessage(boolean show);

    boolean startOnboarding(OnboardingType onboardingType);

    void setShowOnboardingType(OnboardingType onboardingType, boolean state);

    void resetOnboarding();

    boolean startWarningEditMessage();

    void setShowWarningEditMessage(boolean show);

    //
    // Click to call description
    //
    default boolean showClickToCallDescription() {
        return false;
    }

    void setReady();

    LastVersion loadLastVersion();

    @NonNull
    JobService.ProcessingLock allocateProcessingLock();

    @NonNull
    String getApplicationName();


    boolean getDisplayNotificationContent();

    @Nullable
    TwinmeContext getTwinmeContext();

    boolean isRunning();

    void setNotRunning();

    void stop();

    void restart();

    @NonNull
    String getAnonymousName();

    @NonNull
    Bitmap getAnonymousAvatar();

    @NonNull
    Bitmap getDefaultAvatar();

    @NonNull
    Bitmap getDefaultGroupAvatar();

    void setDefaultProfile(@Nullable Profile profile);

    @NonNull
    NotificationCenter newNotificationCenter(@NonNull TwinmeContext twinmeContext);

    /**
     * Check if the feature is enabled for the application.
     *
     * @param feature the feature identification.
     * @return true if the feature is enabled.
     */
    boolean isFeatureSubscribed(@NonNull Feature feature);
}
