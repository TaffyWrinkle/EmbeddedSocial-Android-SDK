/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for license information.
 */

package com.microsoft.embeddedsocial.data.storage.request.wrapper.relationship;

import com.microsoft.embeddedsocial.data.storage.UserCache;
import com.microsoft.embeddedsocial.data.storage.request.wrapper.AbstractBatchNetworkMethodWrapper;
import com.microsoft.embeddedsocial.server.model.UsersListResponse;
import com.microsoft.embeddedsocial.server.model.relationship.GetFollowingFeedRequest;

import java.sql.SQLException;

public class FollowingFeedRequestWrapper
        extends AbstractBatchNetworkMethodWrapper<GetFollowingFeedRequest, UsersListResponse> {

    private final UserCache userCache;
    private final UserCache.UserFeedType feedType;

    public FollowingFeedRequestWrapper(
            INetworkMethod<GetFollowingFeedRequest, UsersListResponse> networkMethod,
            UserCache userCache, UserCache.UserFeedType feedType) {

        super(networkMethod);
        this.userCache = userCache;
        this.feedType = feedType;
    }

    @Override
    protected void storeResponse(GetFollowingFeedRequest request,
                                 UsersListResponse response) throws SQLException {

        userCache.storeUserFeed(request, feedType, response);
    }

    @Override
    protected UsersListResponse getCachedResponse(GetFollowingFeedRequest request)
            throws SQLException {

        return userCache.getResponse(feedType, request.getQueryUserHandle());
    }
}
