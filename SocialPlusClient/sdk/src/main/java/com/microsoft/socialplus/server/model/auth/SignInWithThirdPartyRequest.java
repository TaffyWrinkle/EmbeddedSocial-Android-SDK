/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for license information.
 *
 */

package com.microsoft.socialplus.server.model.auth;

import com.microsoft.socialplus.autorest.models.IdentityProvider;
import com.microsoft.socialplus.autorest.models.PostSessionRequest;
import com.microsoft.socialplus.autorest.models.PostSessionResponse;
import com.microsoft.rest.ServiceException;
import com.microsoft.rest.ServiceResponse;
import com.microsoft.socialplus.server.exception.NetworkRequestException;
import com.microsoft.socialplus.server.model.UserRequest;

import java.io.IOException;

/**
 *
 */
public class SignInWithThirdPartyRequest extends UserRequest {

	private final PostSessionRequest request;

	public SignInWithThirdPartyRequest(
			IdentityProvider identityProvider,
			String thirdPartyAccessToken) {
		request = new PostSessionRequest();
		request.setIdentityProvider(identityProvider);
		request.setAccessToken(thirdPartyAccessToken);
		//TODO
		//request.setRequestToken(requestToken);
		request.setInstanceId(instanceId);
		request.setCreateUser(false);
	}

	@Override
	public AuthenticationResponse send() throws NetworkRequestException {
		ServiceResponse<PostSessionResponse> serviceResponse;
		try {
			serviceResponse = SESSION.postSession(request, appKey, bearerToken, null);
		} catch (ServiceException|IOException e) {
			throw new NetworkRequestException(e.getMessage());
		}

		if (!request.getCreateUser() && serviceResponse.getResponse().code() == 404) {
			request.setCreateUser(true);
			return send();
		}

		checkResponseCode(serviceResponse);

		return new AuthenticationResponse(serviceResponse.getBody());
	}
}
