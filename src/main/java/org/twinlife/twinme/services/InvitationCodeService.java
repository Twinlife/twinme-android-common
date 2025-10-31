/*
 *  Copyright (c) 2025 twinlife SA.
 *  SPDX-License-Identifier: AGPL-3.0-only
 *
 *  Contributors:
 *   Romain Kolb (romain.kolb@skyrock.com)
 */

package org.twinlife.twinme.services;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.twinlife.twinlife.BaseService;
import org.twinlife.twinlife.Filter;
import org.twinlife.twinlife.ImageId;
import org.twinlife.twinlife.ImageService;
import org.twinlife.twinlife.RepositoryObject;
import org.twinlife.twinlife.TwincodeOutbound;
import org.twinlife.twinme.TwinmeContext;
import org.twinlife.twinme.models.Contact;
import org.twinlife.twinme.models.Invitation;
import org.twinlife.twinme.models.Profile;
import org.twinlife.twinme.models.Space;
import org.twinlife.twinme.ui.TwinmeActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

public class InvitationCodeService extends AbstractTwinmeService {
    private static final String LOG_TAG = "InvitationCodeService";
    private static final boolean DEBUG = false;

    private static final int DEFAULT_VALIDITY_PERIOD = 24; //hours

    private static final int LIMIT_INVITATION_CODE = 5;
    private static final int LIMIT_INVITATION_CODE_PREMIUM = 10;
    private static final int PERIOD_TO_DELETE = 48;

    private static final int CREATE_INVITATION = 1;
    private static final int CREATE_INVITATION_DONE = 1 << 1;
    private static final int CREATE_INVITATION_CODE = 1 << 2;
    private static final int CREATE_INVITATION_CODE_DONE = 1 << 3;
    private static final int UPDATE_INVITATION = 1 << 4;
    private static final int UPDATE_INVITATION_DONE = 1 << 5;
    private static final int GET_INVITATION_CODE = 1 << 6;
    private static final int GET_INVITATION_CODE_DONE = 1 << 7;
    private static final int GET_INVITATIONS = 1 << 8;
    private static final int GET_INVITATIONS_DONE = 1 << 9;
    private static final int DELETE_INVITATION = 1 << 10;
    private static final int DELETE_INVITATION_DONE = 1 << 11;
    private static final int GET_CURRENT_SPACE = 1 << 12;
    private static final int GET_CURRENT_SPACE_DONE = 1 << 13;
    private static final int CREATE_CONTACT = 1 << 14;
    private static final int CREATE_CONTACT_DONE = 1 << 15;
    private static final int GET_TWINCODE_IMAGE =  1 << 16;
    private static final int GET_TWINCODE_IMAGE_DONE =  1 << 17;
    private static final int COUNT_VALID_INVITATIONS =  1 << 18;
    private static final int COUNT_VALID_INVITATIONS_DONE =  1 << 19;

    public interface Observer extends AbstractTwinmeService.Observer {

        void onCreateInvitationWithCode(@Nullable Invitation invitation);

        void onGetInvitationCode(@Nullable TwincodeOutbound twincodeOutbound, @Nullable Bitmap avatar, @Nullable String publicKey);

        void onGetInvitationCodeNotFound();

        void onGetLocalInvitationCode();

        void onGetInvitations(@Nullable List<Invitation> invitations);

        void onGetDefaultProfile(@NonNull Profile profile);

        void onGetDefaultProfileNotFound();

        void onDeleteInvitation(@NonNull UUID invitationId);

        void onCreateContact(@NonNull Contact contact);

        default void onLimitInvitationCodeReach() {

        }
    }

    private class TwinmeContextObserver extends AbstractTwinmeService.TwinmeContextObserver {

        @Override
        public void onCreateInvitationWithCode(long requestId, @NonNull Invitation invitation) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onCreateInvitationCode: requestId=" + requestId + " invitation=" + invitation);
            }

            InvitationCodeService.this.onCreateInvitationWithCode(invitation);
        }

        @Override
        public void onGetInvitationCode(long requestId, @NonNull TwincodeOutbound twincodeOutbound, @Nullable String publicKey) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onGetInvitationCode: requestId=" + requestId + " twincodeOutbound=" + twincodeOutbound);
            }

            InvitationCodeService.this.onGetInvitationCode(twincodeOutbound, publicKey);
        }

        @Override
        public void onDeleteInvitation(long requestId, @NonNull UUID invitationId) {
            if (DEBUG) {
                Log.d(LOG_TAG, "onDeleteInvitation: requestId=" + requestId + " invitationId=" + invitationId);
            }

            InvitationCodeService.this.onDeleteInvitation(invitationId);
        }

        @Override
        public void onCreateContact(long requestId, @NonNull Contact contact) {
            if (DEBUG) {
                Log.d(LOG_TAG, "TwinmeContextObserver.onCreateContact: requestId=" + requestId + " contact=" + contact);
            }

            if (getOperation(requestId) == null) {

                return;
            }

            InvitationCodeService.this.onCreateContact(contact);
        }

        @Override
        public void onError(long requestId, BaseService.ErrorCode errorCode, String errorParameter) {
            if (DEBUG) {
                Log.d(LOG_TAG, "InvitationCodeService.TwinmeContextObserver.onError: requestId=" + requestId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
            }

            Integer operationId = getOperation(requestId);
            if (operationId == null) {

                return;
            }

            InvitationCodeService.this.onError(operationId, errorCode, errorParameter);
        }
    }


    @Nullable
    private Observer mObserver;
    @Nullable
    private Invitation mInvitation;
    private int mValidityPeriod;
    @Nullable
    private String mCode;

    @Nullable
    private TwincodeOutbound mTwincodeOutbound;
    @Nullable
    private String mPublicKey;
    @Nullable
    private ImageId mTwincodeAvatarId;

    @Nullable
    private List<Invitation> mInvitations;
    private List<Invitation> mInvitationsToDelete;

    @Nullable
    private Space mSpace;
    @Nullable
    private Profile mProfile;

    private int mInvitationCodeLimit = 0;

    private int mState = 0;
    private int mWork = 0;

    public InvitationCodeService(@NonNull TwinmeActivity activity, @NonNull TwinmeContext twinmeContext, @NonNull InvitationCodeService.Observer observer) {
        super(LOG_TAG, activity, twinmeContext, observer);
        if (DEBUG) {
            Log.d(LOG_TAG, "InvitationCodeService: activity=" + activity + " twinmeContext=" + twinmeContext + " observer=" + observer);
        }

        mObserver = observer;

        mTwinmeContextObserver = new TwinmeContextObserver();

        mTwinmeContext.setObserver(mTwinmeContextObserver);
    }

    public void createInvitationWithCode(boolean isPremiumVersion) {

        createInvitationWithCode(isPremiumVersion ? LIMIT_INVITATION_CODE_PREMIUM : LIMIT_INVITATION_CODE , DEFAULT_VALIDITY_PERIOD);
    }

    public void createInvitationWithCode(int limit, int validityPeriod) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createInvitationCode: validityPeriod=" + validityPeriod);
        }

        mInvitationCodeLimit = limit;
        mValidityPeriod = validityPeriod;

        mWork |= COUNT_VALID_INVITATIONS;
        mState &= ~(COUNT_VALID_INVITATIONS | COUNT_VALID_INVITATIONS_DONE);

        startOperation();
    }

    public void getInvitationCode(@NonNull String code) {
        if (DEBUG) {
            Log.d(LOG_TAG, "getInvitationCode: code=" + code);
        }

        if (mInvitations != null) {
            for (Invitation invitation : mInvitations) {
                if (invitation.getInvitationCode() != null && code.equals(invitation.getInvitationCode().getCode())) {
                    runOnUiThread(() -> {
                        if (mObserver != null) {
                            mObserver.onGetLocalInvitationCode();
                        }
                    });
                    return;
                }
            }
        }

        mCode = code;

        mWork |= GET_INVITATION_CODE;
        mState &= ~(GET_INVITATION_CODE | GET_INVITATION_CODE_DONE);

        startOperation();
    }

    public void getInvitations() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getInvitations");
        }

        mWork |= GET_INVITATIONS;
        mState &= ~(GET_INVITATIONS | GET_INVITATIONS_DONE);

        startOperation();
    }

    public void deleteInvitation(Invitation invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "deleteInvitation");
        }

        mInvitation = invitation;

        mWork |= DELETE_INVITATION;
        mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);

        startOperation();
    }

    public void createContact(TwincodeOutbound twincodeOutbound) {
        if (DEBUG) {
            Log.d(LOG_TAG, "createContact: " + twincodeOutbound);
        }

        mTwincodeOutbound = twincodeOutbound;

        mWork |= CREATE_CONTACT;
        mState &= ~(CREATE_CONTACT | CREATE_CONTACT_DONE);

        startOperation();
    }

    @Override
    public void dispose() {
        if (DEBUG) {
            Log.d(LOG_TAG, "dispose");
        }

        mObserver = null;
        super.dispose();
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
        // Step 1: get the current space.
        //

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

        if ((mWork & GET_INVITATIONS) != 0) {
            if ((mState & GET_INVITATIONS) == 0) {
                mState |= GET_INVITATIONS;

                Filter<RepositoryObject> filter = new Filter<RepositoryObject>(mSpace) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Invitation)) {
                            return false;
                        }

                        final Invitation invitation = (Invitation) object;
                        return invitation.getInvitationCode() != null;
                    }
                };

                mTwinmeContext.findInvitations(filter,
                        this::onGetInvitations);
            }

            if ((mState & GET_INVITATIONS_DONE) == 0) {
                return;
            }
        }

        if ((mWork & COUNT_VALID_INVITATIONS) != 0) {
            if ((mState & COUNT_VALID_INVITATIONS) == 0) {
                mState |= COUNT_VALID_INVITATIONS;

                Filter<RepositoryObject> filter = new Filter<RepositoryObject>(null) {
                    @Override
                    public boolean accept(@NonNull RepositoryObject object) {
                        if (!(object instanceof Invitation)) {
                            return false;
                        }

                        final Invitation invitation = (Invitation) object;

                        if (invitation.getInvitationCode() == null) {
                            return false;
                        }

                        Calendar calendarExpiration = Calendar.getInstance();
                        long expirationDate = (invitation.getCreationDate() / 1000) + (60L * 60 * invitation.getInvitationCode().getValidityPeriod());
                        calendarExpiration.setTimeInMillis(expirationDate * 1000L);

                        return calendarExpiration.after(Calendar.getInstance());
                    }
                };

                mTwinmeContext.findInvitations(filter,
                        (List<Invitation> invitations) -> {
                            onCountValidInvitation(invitations.size());
                            onOperation();
                        });
            }

            if ((mState & GET_INVITATIONS_DONE) == 0) {
                return;
            }
        }

        //
        // Work Step: create invitation code
        //

        if ((mWork & CREATE_INVITATION_CODE) != 0) {

            if ((mState & CREATE_INVITATION_CODE) == 0) {
                mState |= CREATE_INVITATION_CODE;

                mTwinmeContext.createInvitationWithCode(newOperation(CREATE_INVITATION_CODE), mValidityPeriod);
                return;
            }
            if ((mState & CREATE_INVITATION_CODE_DONE) == 0) {
                return;
            }
        }

        //
        // Work Step: get invitation code
        //
        if ((mWork & GET_INVITATION_CODE) != 0) {
            if ((mState & GET_INVITATION_CODE) == 0) {
                mState |= GET_INVITATION_CODE;

                if (mCode == null) {
                    mTwinmeContext.assertion(ServiceAssertPoint.GET_INVITATION_CODE, null);
                    return;
                }

                long requestId = newOperation(GET_INVITATION_CODE);
                mTwinmeContext.getInvitationCode(requestId, mCode);
                return;
            }

            if ((mState & GET_INVITATION_CODE_DONE) == 0) {
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
                mTwinmeContext.getImageService().getImageFromServer(mTwincodeAvatarId, ImageService.Kind.THUMBNAIL, (BaseService.ErrorCode errorCode, Bitmap image) -> {
                    onGetTwincodeImage(image);
                    mState |= GET_TWINCODE_IMAGE_DONE;
                    onOperation();
                });
                return;
            }
            if ((mState & GET_TWINCODE_IMAGE_DONE) == 0) {
                return;
            }
        }

        if ((mWork & DELETE_INVITATION) != 0) {
            if ((mState & DELETE_INVITATION) == 0) {
                mState |= DELETE_INVITATION;

                if (mInvitation == null) {
                    mTwinmeContext.assertion(ServiceAssertPoint.DELETE_INVITATION_CODE, null);
                    return;
                }

                long requestId = newOperation(DELETE_INVITATION);

                mTwinmeContext.deleteInvitation(requestId, mInvitation);

                return;
            }

            if ((mState & DELETE_INVITATION_DONE) == 0) {
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
                mTwinmeContext.createContactPhase1(requestId, mTwincodeOutbound, mSpace, mProfile, null);
                return;
            }

            if ((mState & CREATE_CONTACT_DONE) == 0) {
                return;
            }
        }

        //
        // Last Step
        //

        hideProgressIndicator();
    }

    private void onCreateInvitationWithCode(@NonNull Invitation invitation) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCreateInvitationCode: invitation=" + invitation);
        }

        mState |= CREATE_INVITATION_CODE_DONE;

        mInvitation = invitation;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onCreateInvitationWithCode(mInvitation);
            }
        }) ;

        onOperation();
    }

    private void onGetInvitations(@NonNull List<Invitation> invitations) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetInvitations: invitations=" + invitations);
        }

        mState |= GET_INVITATIONS_DONE;

        if (mInvitations == null) {
            mInvitations = new ArrayList<>();
            mInvitationsToDelete = new ArrayList<>();
        }

        mInvitations.clear();
        mInvitationsToDelete.clear();

        Calendar calendar = Calendar.getInstance();

        for (Invitation invitation : invitations) {

            if (invitation.getInvitationCode() != null) {
                Calendar calendarDelete = Calendar.getInstance();
                long expirationDate = (invitation.getCreationDate() / 1000) + (60L * 60 * invitation.getInvitationCode().getValidityPeriod());
                calendarDelete.setTimeInMillis(expirationDate * 1000L);
                calendarDelete.add(Calendar.HOUR, PERIOD_TO_DELETE);

                if (calendarDelete.after(calendar)) {
                    mInvitations.add(invitation);
                } else {
                    mInvitationsToDelete.add(invitation);
                }
            }

        }

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetInvitations(mInvitations);
            }
        });

        if (!mInvitationsToDelete.isEmpty()) {
            nextInvitationToDelete();
        }

        onOperation();
    }

    private void onGetInvitationCode(@NonNull TwincodeOutbound twincodeOutbound, @Nullable String publicKey) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetInvitationCode: twincodeOutbound=" + twincodeOutbound + " publicKey=" + publicKey);
        }

        mState |= GET_INVITATION_CODE_DONE;
        mTwincodeOutbound = twincodeOutbound;
        mPublicKey = publicKey;

        if (mTwincodeOutbound.getAvatarId() == null) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetInvitationCode(twincodeOutbound, null, publicKey);
                }
            });
        } else {
            mTwincodeAvatarId = mTwincodeOutbound.getAvatarId();
            mState &= ~(GET_TWINCODE_IMAGE | GET_TWINCODE_IMAGE_DONE);
        }

        onOperation();
    }

    private void onGetTwincodeImage(@Nullable Bitmap bitmap) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetTwincodeImage: bitmap=" + bitmap);
        }

        mState |= GET_TWINCODE_IMAGE_DONE;

        runOnUiThread(() -> {
            if (mObserver != null) {
                mObserver.onGetInvitationCode(mTwincodeOutbound, bitmap, mPublicKey);
            }
        });
    }

    private void onDeleteInvitation(@NonNull UUID invitationId) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onDeleteInvitation: invitationId=" + invitationId);
        }

        mState |= DELETE_INVITATION_DONE;

        if (mInvitationsToDelete != null && !mInvitationsToDelete.isEmpty()) {
            nextInvitationToDelete();
        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onDeleteInvitation(invitationId);
                }
            });
        }

        onOperation();
    }

    private void nextInvitationToDelete() {
        if (DEBUG) {
            Log.d(LOG_TAG, "nextInvitationToDelete");
        }

        mInvitation = mInvitationsToDelete.remove(0);
        mWork |= DELETE_INVITATION;
        mState &= ~(DELETE_INVITATION | DELETE_INVITATION_DONE);
        onOperation();
    }

    private void onGetCurrentSpace(@NonNull BaseService.ErrorCode errorCode, @Nullable Space space) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetCurrentSpace: space=" + space);
        }

        mState |= GET_CURRENT_SPACE_DONE;
        mSpace = space;
        onGetProfile(space == null ? null : space.getProfile());
        onOperation();
    }

    private void onGetProfile(@Nullable Profile profile) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onGetProfile: profile=" + profile);
        }

        if (profile != null) {
            mProfile = profile;
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDefaultProfile(profile);
                }
            });
        } else {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetDefaultProfileNotFound();
                }
            });
        }
        onOperation();
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

    private void onCountValidInvitation(int count) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onCountValidInvitation: count=" + count);
        }

        mState |= COUNT_VALID_INVITATIONS_DONE;

        if (count >= mInvitationCodeLimit) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onLimitInvitationCodeReach();
                }
            });
        } else {
            mWork |= CREATE_INVITATION_CODE;
            mState &= ~(CREATE_INVITATION | CREATE_INVITATION_DONE | CREATE_INVITATION_CODE | CREATE_INVITATION_CODE_DONE | UPDATE_INVITATION | UPDATE_INVITATION_DONE);
        }

        onOperation();
    }

    @Override
    protected void onError(int operationId, BaseService.ErrorCode errorCode, @Nullable String errorParameter) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onError: operationId=" + operationId + " errorCode=" + errorCode + " errorParameter=" + errorParameter);
        }

        if (errorCode == BaseService.ErrorCode.ITEM_NOT_FOUND && operationId == GET_INVITATION_CODE) {
            runOnUiThread(() -> {
                if (mObserver != null) {
                    mObserver.onGetInvitationCodeNotFound();
                }
            });

            return;
        }


        super.onError(operationId, errorCode, errorParameter);
        onOperation();
    }
}
