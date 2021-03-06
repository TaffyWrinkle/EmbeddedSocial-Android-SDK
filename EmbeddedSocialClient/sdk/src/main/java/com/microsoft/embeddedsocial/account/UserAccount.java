/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for license information.
 */

package com.microsoft.embeddedsocial.account;

import com.facebook.login.LoginManager;
import com.microsoft.embeddedsocial.auth.MicrosoftLiveAuthenticator;
import com.microsoft.embeddedsocial.auth.SocialNetworkTokens;
import com.microsoft.embeddedsocial.autorest.models.FollowerStatus;
import com.microsoft.embeddedsocial.base.GlobalObjectRegistry;
import com.microsoft.embeddedsocial.base.event.EventBus;
import com.microsoft.embeddedsocial.data.Preferences;
import com.microsoft.embeddedsocial.data.model.AccountData;
import com.microsoft.embeddedsocial.data.storage.DatabaseHelper;
import com.microsoft.embeddedsocial.data.storage.UserActionProxy;
import com.microsoft.embeddedsocial.event.RequestSignInEvent;
import com.microsoft.embeddedsocial.event.relationship.UserBlockedEvent;
import com.microsoft.embeddedsocial.event.relationship.UserFollowedStateChangedEvent;
import com.microsoft.embeddedsocial.event.relationship.UserUnblockedEvent;
import com.microsoft.embeddedsocial.event.signin.CreateUserFailedEvent;
import com.microsoft.embeddedsocial.event.signin.SignInWithThirdPartyFailedEvent;
import com.microsoft.embeddedsocial.event.signin.UserSignedInEvent;
import com.microsoft.embeddedsocial.pending.PendingAction;
import com.microsoft.embeddedsocial.pending.PendingBlock;
import com.microsoft.embeddedsocial.pending.PendingFollow;
import com.microsoft.embeddedsocial.server.model.view.UserCompactView;
import com.microsoft.embeddedsocial.service.worker.SignInWorker;
import com.microsoft.embeddedsocial.service.worker.SignOutWorker;
import com.microsoft.embeddedsocial.service.worker.WorkerHelper;
import com.microsoft.embeddedsocial.ui.util.NotificationCountChecker;
import com.microsoft.embeddedsocial.ui.util.SocialNetworkAccount;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.CookieManager;

import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;

/**
 * Manages functionality related to user account.
 */
public class UserAccount {

    private final Context context;

    private String userHandle;
    private AccountData accountDetails;
    private UserActionProxy userActionProxy;

    public UserAccount(Context context) {
        this.context = context;
        userHandle = Preferences.getInstance().getUserHandle();
        accountDetails = AccountDataStorage.get(context);
        userActionProxy = new UserActionProxy(context);
    }

    /**
     * Launches sign-in process via third party account.
     */
    public Operation signInUsingThirdParty(SocialNetworkAccount thirdPartyAccount) {
        Data inputData = new Data.Builder()
                .putString(SignInWorker.SOCIAL_NETWORK_ACCOUNT,
                        WorkerHelper.serialize(thirdPartyAccount)).build();
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(SignInWorker.class)
                .setInputData(inputData).addTag(SignInWorker.TAG).build();
        return WorkManager.getInstance().enqueue(workRequest);
    }

    /**
     * Called on sign-in process completed.
     * @param newUserHandle current user's handle
     * @param sessionToken current user's sessionToken
     * @param newAccountDetails current user's account details
     */
    public void onSignedIn(String newUserHandle, String sessionToken, AccountData newAccountDetails,
                           int messageId) {
        GlobalObjectRegistry.getObject(DatabaseHelper.class).clearData();
        setNewAccountData(newUserHandle, sessionToken, newAccountDetails, messageId);
        PendingAction postponedAction = Preferences.getInstance().getPendingAction();
        if (postponedAction != null) {
            postponedAction.execute(context);
        }
        Preferences.getInstance().clearPendingAction();
    }

    private void setNewAccountData(String newUserHandle, String sessionToken,
                                   AccountData newAccountDetails, int messageId) {
        userHandle = newUserHandle;
        accountDetails = newAccountDetails;
        AccountDataStorage.store(context, newAccountDetails);
        Preferences.getInstance().setUserHandle(newUserHandle);
        Preferences.getInstance().setAuthorizationToken(sessionToken);
        EventBus.post(new UserSignedInEvent(messageId));
    }

    /**
     * Called when sign-in process failed.
     */
    public void onSignInWithThirdPartyFailed() {
        EventBus.post(new SignInWithThirdPartyFailedEvent());
    }

    /**
     * Called when create user process failed
     */
    public void onCreateUserFailed() {
        EventBus.post(new CreateUserFailedEvent());
    }

    /**
     * Whether sign-in is in progress now.
     */
    public boolean isSigningIn() {
        return WorkerHelper.isOngoing(SignInWorker.TAG);
    }

    /**
     * Whether the current user is signed-in.
     */
    public boolean isSignedIn() {
        return !TextUtils.isEmpty(userHandle);
    }

    /**
     * Returns current user's account details.
     */
    public AccountData getAccountDetails() {
        return accountDetails;
    }

    /**
     * Clears all the data associated with the current user (except the data in the database) and launch the request to the server to sign-out.
     */
    public void signOut() {
        Data inputData = new Data.Builder()
                .putString(SignOutWorker.AUTHORIZATION, Preferences.getInstance().getAuthorizationToken())
                .build();
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(SignOutWorker.class)
                .setInputData(inputData).build();
        WorkManager.getInstance().enqueue(workRequest);

        signOutOfDevice();
    }

    /**
     * Clears all the data associated with the current user (except the data in the database)
     */
    public void signOutOfDevice() {
        MicrosoftLiveAuthenticator.signOut(context);
        clearCookies();
        AccountDataStorage.clear(context);
        Preferences.getInstance().setUserHandle(null);
        Preferences.getInstance().setAuthorizationToken(null);
        Preferences.getInstance().resetNotificationCount();
        accountDetails = null;
        userHandle = null;
        NotificationCountChecker.reset();
        NotificationManagerCompat.from(context).cancelAll();
        LoginManager.getInstance().logOut();
        SocialNetworkTokens.clearAll();
    }

    /**
     * Removes all cookies
     */
    private void clearCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.removeAllCookies(null);
        } else {
            //noinspection deprecation
            cookieManager.removeAllCookie();
        }
    }

    /**
     * Replaces the current user's account details.
     */
    public void updateAccountDetails(AccountData newAccountDetails) {
        accountDetails = newAccountDetails;
        AccountDataStorage.store(context, newAccountDetails);
    }

    /**
     * Returns whether <code>someUserHandle</code> corresponds to the current user.
     */
    public boolean isCurrentUser(String someUserHandle) {
        return userHandle != null && userHandle.equals(someUserHandle);
    }

    public static UserAccount getInstance() {
        return GlobalObjectRegistry.getObject(UserAccount.class);
    }

    /**
     * Gets user handle of the current user.
     * @return  user handle.
     */
    public String getUserHandle() {
        return userHandle;
    }

    /**
     * Generates a {@linkplain UserCompactView} object based on current user profile.
     * @return  {@linkplain UserCompactView} instance.
     */
    public UserCompactView generateCompactUserView() {
        UserCompactView user = new UserCompactView();
        user.setUserHandle(userHandle);
        user.setFirstName(accountDetails.getFirstName());
        user.setLastName(accountDetails.getLastName());
        user.setUserPhotoUrl(accountDetails.getUserPhotoUrl());
        user.setIsPrivate(accountDetails.isPrivate());
        return user;
    }

    /**
     * Inits a follow request. If the user is not signed-in the sign-in page will be shown and the action will be executed after authorization or cancelled
     * if the user cancels authorization.
     */
    public boolean followUser(Fragment fragment, String anotherUserHandle, AccountData user) {
        return followUser(fragment, anotherUserHandle, user.isPrivate());
    }

    /**
     * Inits a follow request. If the user is not signed-in the sign-in page will be shown and the action will be executed after authorization or cancelled
     * if the user cancels authorization.
     */
    public boolean followUser(Fragment fragment, UserCompactView user) {
        return followUser(fragment, user.getHandle(), user.isPrivate());
    }

    private boolean followUser(Fragment fragment, String anotherUserHandle, boolean isPrivate) {
        if (checkAuthorization(fragment, AuthorizationCause.FOLLOW)) {
            userActionProxy.followUser(anotherUserHandle);
            EventBus.post(new UserFollowedStateChangedEvent(anotherUserHandle, isPrivate ? FollowerStatus.PENDING : FollowerStatus.FOLLOW));
            return true;
        } else {
            Preferences.getInstance().setPendingAction(new PendingFollow(anotherUserHandle));
            return false;
        }
    }

    /**
     * Inits an unfollow request.
     */
    public void unfollowUser(String anotherUserHandle) {
        userActionProxy.unfollowUser(anotherUserHandle);
        EventBus.post(new UserFollowedStateChangedEvent(anotherUserHandle, FollowerStatus.NONE));
    }

    /**
     * Blocks a user. If the user is not signed-in the sign-in page will be shown and the action will be executed after authorization or cancelled
     * if the user cancels authorization.
     */
    public void blockUser(Fragment fragment, String anotherUserHandle) {
        if (checkAuthorization(fragment, AuthorizationCause.BLOCK)) {
            userActionProxy.blockUser(anotherUserHandle);
            EventBus.post(new UserBlockedEvent(anotherUserHandle));
        } else {
            Preferences.getInstance().setPendingAction(new PendingBlock(anotherUserHandle));
        }
    }

    /**
     * Unblocks a user.
     */
    public void unblockUser(String anotherUserHandle) {
        userActionProxy.unblockUser(anotherUserHandle);
        EventBus.post(new UserUnblockedEvent(anotherUserHandle));
    }

    /**
     * Accepts a follow request.
     */
    public void acceptFollowRequest(String anotherUserHandle) {
        userActionProxy.acceptUser(anotherUserHandle);
    }

    /**
     * Rejects a follow request.
     */
    public void rejectFollowRequest(String anotherUserHandle) {
        userActionProxy.rejectUser(anotherUserHandle);
    }

    /**
     * Checks if the user is signed-in and launches sign-in if the user is not signed-in.
     */
    public boolean checkAuthorization(Fragment fragment, AuthorizationCause authorizationCause) {
        boolean signedIn = isSignedIn();
        if (!signedIn) {
            EventBus.post(new RequestSignInEvent(fragment, authorizationCause));
        }
        return signedIn;
    }

}
