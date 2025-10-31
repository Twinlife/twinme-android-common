/*
 *  Copyright (c) 2024 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.calls.keycheck;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService.ErrorCode;
import org.twinlife.twinlife.RepositoryObjectFactory;
import org.twinlife.twinlife.RepositoryService;
import org.twinlife.twinlife.TrustMethod;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinlife.TwincodeURI;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.calls.CallConnection;
import org.twinlife.twinme.calls.CallParticipantEvent;
import org.twinlife.twinme.calls.CallParticipantObserver;
import org.twinlife.twinme.calls.CallState;
import org.twinlife.twinme.models.Capabilities;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.ContactFactory;
import org.twinlife.twinme.models.Originator;
import org.twinlife.twinme.utils.MnemonicCodeUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

public class KeyCheckSessionHandler {
    private static final String LOG_TAG = "KeyCheckSessionHandler";
    private static final boolean DEBUG = false;

    @NonNull
    private static final Handler uiThreadHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final TwinmeContext mTwinmeContext;
    @NonNull
    private final Locale mLanguage;
    @NonNull
    private final Context mContext;
    @Nullable
    private final CallParticipantObserver mCallParticipantObserver;
    @NonNull
    private final CallState mCall;
    @Nullable
    private CallConnection mCallConnection;
    @Nullable
    private KeyCheckSession mKeyCheckSession;
    @Nullable
    private TwincodeURI mTwincodeURI = null;

    public KeyCheckSessionHandler(@NonNull Context context, @NonNull TwinmeContext twinmeContext, @Nullable CallParticipantObserver callParticipantObserver, @NonNull CallState call, @NonNull Locale language) {
        mContext = context;
        mTwinmeContext = twinmeContext;
        mCallParticipantObserver = callParticipantObserver;
        mCall = call;
        mLanguage = language;
    }

    /**
     * Initiate a key check session from this device
     *
     * @throws IllegalStateException if we're not in a 1-on-1 call, or the peer's twincode is not signed.
     */
    public void initSession() throws IllegalStateException {
        if (DEBUG) {
            Log.d(LOG_TAG, "initSession");
        }

        if (mCall.isGroupCall()  || !mCall.isVideo()) {
            throw new IllegalStateException("Key checking is only supported in 1-on-1 video calls.");
        }

        final Originator originator = mCall.getOriginator();
        if (originator == null || originator.getType() != Originator.Type.CONTACT) {
            throw new IllegalStateException("Key checking only supported for contacts but originator is " + mCall.getOriginator());
        }

        mCallConnection = mCall.getInitialConnection();
        if (mCallConnection == null) {
            throw new IllegalStateException("mCall " + mCall + " has no CallConnection");
        }

        TwincodeOutbound twincodeOutbound = originator.getTwincodeOutbound();
        if (twincodeOutbound == null) {
            throw new IllegalStateException("Call originator " + originator + " has no twincodeOutbound");
        }

        if (!twincodeOutbound.isSigned()) {
            throw new IllegalStateException("twincodeOutbound " + twincodeOutbound + " is not signed");
        }

        mTwinmeContext.getTwincodeOutboundService().createURI(TwincodeURI.Kind.Authenticate, twincodeOutbound, (status, twincodeURI) -> {
            if (status != ErrorCode.SUCCESS || twincodeURI == null) {
                Log.e(LOG_TAG, "Could not create twincode URI: " + status);
                return;
            }

            mTwincodeURI = twincodeURI;

            byte[] hashBytes;
            try {
                hashBytes = MessageDigest.getInstance("SHA-256").digest(twincodeURI.label.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                Log.e(LOG_TAG, "Error computing digest for hash:" + twincodeURI.label + ", this should not happen", e);
                return;
            }

            List<String> words = new MnemonicCodeUtils(mContext).xorAndMnemonic(hashBytes, mLanguage);
            try {
                mKeyCheckSession = new KeyCheckSession(words, true);
            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, "Invalid mnemonic " + String.join(", ", words), e);
                return;
            }

            mCallConnection.sendKeyCheckInitiateIQ(mLanguage);
        });
    }

    public void initSession(@NonNull CallConnection callConnection) {
        if (DEBUG) {
            Log.d(LOG_TAG, "initSession: callConnection=" + callConnection);
        }

        mCallConnection = callConnection;

        if (!mCall.equals(callConnection.getCall()) || mCall.isGroupCall() || !mCall.isVideo()) {
            callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.BAD_REQUEST);
            return;
        }

        final Originator originator = mCall.getOriginator();
        if (originator == null || originator.getType() != Originator.Type.CONTACT) {
            callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.BAD_REQUEST);
            return;
        }

        final TwincodeOutbound twincodeOutbound = callConnection.getOriginator() != null ? callConnection.getOriginator().getTwincodeOutbound() : null;

        if (twincodeOutbound == null) {
            callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.LIBRARY_ERROR);
            return;
        }

        if (!twincodeOutbound.isSigned()) {
            callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.NO_PUBLIC_KEY);
            return;
        }

        mTwinmeContext.getTwincodeOutboundService().createURI(TwincodeURI.Kind.Authenticate, twincodeOutbound, (status, twincodeURI) -> {
            if (status != ErrorCode.SUCCESS || twincodeURI == null) {
                callConnection.sendOnKeyCheckInitiateIQ(status);
                return;
            }

            mTwincodeURI = twincodeURI;

            byte[] hashBytes;
            try {
                hashBytes = MessageDigest.getInstance("SHA-256").digest(twincodeURI.label.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                Log.e(LOG_TAG, "Error computing digest for hash:" + twincodeURI.label + ", this should not happen", e);
                callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.INVALID_PUBLIC_KEY);
                return;
            }

            List<String> words = new MnemonicCodeUtils(mContext).xorAndMnemonic(hashBytes, mLanguage);
            try {
                this.mKeyCheckSession = new KeyCheckSession(words, false);
            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, "Invalid mnemonic " + String.join(", ", words), e);
                callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.BAD_SIGNATURE);
                return;
            }

            callConnection.sendOnKeyCheckInitiateIQ(ErrorCode.SUCCESS);
            sendParticipantEvent(CallParticipantEvent.EVENT_KEY_CHECK_INITIATE);
        });
    }

    @Nullable
    public WordCheckChallenge getCurrentWord() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getCurrentWord");
        }

        if (mKeyCheckSession == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return null;
        }

        return mKeyCheckSession.getCurrentWord();
    }

    @Nullable
    public WordCheckChallenge getPeerError() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getPeerError");
        }

        if (mKeyCheckSession == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return null;
        }

        return mKeyCheckSession.getPeerError();
    }

    /**
     * Check the state of a key check session.
     *
     * @return true if there is an active session and we have the results for all words for both sides, false otherwise.
     */
    public boolean isDone() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isDone");
        }

        if (mKeyCheckSession == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return false;
        }

        return mKeyCheckSession.isDone();
    }

    /**
     * Check the result of a session.
     *
     * @return null if we haven't got all the results, true if all words were confirmed by both sides, false otherwise.
     */
    @Nullable
    public Boolean isOK() {
        if (DEBUG) {
            Log.d(LOG_TAG, "isOK");
        }

        if (mKeyCheckSession == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return null;
        }

        return mKeyCheckSession.isOK();
    }


    // Local (UI) actions
    public void processLocalWordCheckResult(@NonNull WordCheckResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "processLocalWordCheckResult: result=" + result);
        }

        if (mKeyCheckSession == null || mCallConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return;
        }

        WordCheckChallenge currentWord = getCurrentWord();

        if (currentWord == null || currentWord.index != result.wordIndex || !currentWord.checker) {
            Log.e(LOG_TAG, "Got local result " + result + " but we're not the checker for the current word: " + currentWord);
            return;
        }

        mKeyCheckSession.addLocalResult(result);
        mCallConnection.sendWordCheckResultIQ(result);

        sendParticipantEvent(CallParticipantEvent.EVENT_CURRENT_WORD_CHANGED);

        maybeSendTerminateKeyCheck();
    }


    // Peer actions
    public void onPeerWordCheckResult(@NonNull WordCheckResult result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "processPeerWordCheckResult: result=" + result);
        }

        if (mKeyCheckSession == null || mCallConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return;
        }

        WordCheckChallenge currentWord = getCurrentWord();

        if (currentWord == null || currentWord.index != result.wordIndex || currentWord.checker) {
            Log.e(LOG_TAG, "Got peer result " + result + " but we're the checker for the current word: " + currentWord);
            return;
        }

        mKeyCheckSession.addPeerResult(result);

        if (!result.ok) {
            sendParticipantEvent(CallParticipantEvent.EVENT_WORD_CHECK_RESULT_KO);
        }

        sendParticipantEvent(CallParticipantEvent.EVENT_CURRENT_WORD_CHANGED);

        maybeSendTerminateKeyCheck();
    }

    public void onOnKeyCheckInitiate() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onOnKeyCheckInitiate");
        }

        if (mKeyCheckSession == null || mCallConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return;
        }

        sendParticipantEvent(CallParticipantEvent.EVENT_ON_KEY_CHECK_INITIATE);
    }

    public void onTerminateKeyCheck(boolean result) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTerminateKeyCheck: result=" + result);
        }

        if (mKeyCheckSession == null || mCallConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return;
        }

        // TODO: abort check session? send error to activity?
        if (!isDone()) {
            Log.e(LOG_TAG, "Got result: " + result + " but we're not done yet");
        }

        mKeyCheckSession.setPeerResult(result);

        if (mKeyCheckSession.isTerminateSent()) {
            finishSession();
        }
    }

    public void onTwincodeUriIQ(@NonNull String uri) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTwincodeUriIQ: uri=" + uri);
        }

        if (mKeyCheckSession == null || mCallConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return;
        }

        if (!Boolean.TRUE.equals(isOK())) {
            Log.e(LOG_TAG, "Peer sent a twincodeURI but the check is not OK! Ignoring");
            return;
        }

        mTwinmeContext.getTwincodeOutboundService().parseURI(Uri.parse(uri), (status, twincodeURI) -> {
            if (status != ErrorCode.SUCCESS || twincodeURI == null) {
                Log.e(LOG_TAG, "Could not parse URI: " + uri + " status: " + status);
                return;
            }

            mTwinmeContext.verifyContact(twincodeURI, TrustMethod.VIDEO, (status1, verifiedContact) -> {
                if (status1 != ErrorCode.SUCCESS || verifiedContact == null) {
                    Log.e(LOG_TAG, "Couldn't verify contact with twincodeUri: " + twincodeURI + " status: " + status1);
                    return;
                }

                if (DEBUG) {
                    Log.d(LOG_TAG, "Contact is now verified !");
                }
            });
        });
    }

    private void maybeSendTerminateKeyCheck() {
        if (DEBUG) {
            Log.d(LOG_TAG, "maybeSendTerminateKeyCheck");
        }

        if (mKeyCheckSession == null || mCallConnection == null) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Key check session not started");
            }
            return;
        }

        Boolean finalResult = isOK();

        if (finalResult != null) {
            // We have all the local and peer results
            boolean terminateSent = mKeyCheckSession.getAndSetTerminateSent();

            if (!terminateSent) {
                mCallConnection.sendTerminateKeyCheckIQ(finalResult);
            }

            if (mKeyCheckSession.getPeerResult() != null) {
                finishSession();
            }
        }
    }

    final RepositoryObjectFactory<?>[] CONTACT_FACTORY = {
            ContactFactory.INSTANCE
    };

    private void finishSession() {
        if (DEBUG) {
            Log.d(LOG_TAG, "finishSession");
        }

        // We have sent our Terminate and got the peer's Terminate => notify UI that we're done
        sendParticipantEvent(CallParticipantEvent.EVENT_TERMINATE_KEY_CHECK);

        if (Boolean.TRUE.equals(isOK()) && mTwincodeURI != null && mTwincodeURI.pubKey != null) {

            RepositoryService.FindResult result = mTwinmeContext.getRepositoryService().findWithSignature(mTwincodeURI.pubKey, CONTACT_FACTORY);

            if (result.errorCode != ErrorCode.SUCCESS || !(result.object instanceof Contact)) {
                Log.e(LOG_TAG, "Contact not found: errorCode=" + result.errorCode + " object=" + result.object);
                return;
            }

            Contact contact = (Contact) result.object;

            TwincodeOutbound twincodeOutbound = contact.getTwincodeOutbound();
            TwincodeOutbound peerTwincodeOutbound = contact.getPeerTwincodeOutbound();

            if (twincodeOutbound == null) {
                Log.e(LOG_TAG, "Contact " + contact + "has no twincodeOutbound");
                return;
            }

            if (peerTwincodeOutbound == null) {
                Log.e(LOG_TAG, "Contact " + contact + "has no peerTwincodeOutbound");
                return;
            }

            if (peerTrustsUs(contact)) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Peer already trusts us, nothing to do");
                }
                return;
            }


            mTwinmeContext.getTwincodeOutboundService().createURI(TwincodeURI.Kind.Authenticate, twincodeOutbound, (status, twincodeURI) -> {
                if (status != ErrorCode.SUCCESS || twincodeURI == null) {
                    Log.e(LOG_TAG, "Couldn't create twincodeURI for twincodeOutbound: " + twincodeOutbound + ", result=" + status);
                    return;
                }

                if (mCallConnection != null) {
                    mCallConnection.sendTwincodeUriIQ(twincodeURI.uri);
                }
            });
        }
    }

    private boolean peerTrustsUs(@NonNull Contact contact) {
        TwincodeOutbound twincodeOutbound = contact.getTwincodeOutbound();
        TwincodeOutbound peerTwincodeOutbound = contact.getPeerTwincodeOutbound();

        if (twincodeOutbound == null || peerTwincodeOutbound == null) {
            return false;
        }

        Capabilities peerCaps = peerTwincodeOutbound.getCapabilities() != null ? new Capabilities(peerTwincodeOutbound.getCapabilities()) : new Capabilities();

        return peerCaps.isTrusted(twincodeOutbound.getId());
    }

    private void sendParticipantEvent(@NonNull CallParticipantEvent event) {
        if (mCallConnection != null && mCallParticipantObserver != null) {
            uiThreadHandler.post(() -> mCallParticipantObserver.onEventParticipant(mCallConnection.getMainParticipant(), event));
        }
    }
}
