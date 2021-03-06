/*******************************************************************************
 * Copyright 2013 The MITRE Corporation 
 *   and the MIT Kerberos and Internet Trust Consortium
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.mitre.oauth2.service.impl;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import java.util.Date;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mitre.oauth2.model.AuthenticationHolderEntity;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.model.OAuth2RefreshTokenEntity;
import org.mitre.oauth2.repository.AuthenticationHolderRepository;
import org.mitre.oauth2.repository.OAuth2TokenRepository;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.common.exceptions.InvalidClientException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;

import com.google.common.collect.Sets;

/**
 * @author wkim
 *
 */
@RunWith(MockitoJUnitRunner.class)
public class TestDefaultOAuth2ProviderTokenService {

	// Grace period for time-sensitive tests.
	private static final long DELTA = 100L;

	// Test Fixture:
	private OAuth2Authentication authentication;
	private ClientDetailsEntity client;
	private String clientId = "test_client";
	private Set<String> scope = Sets.newHashSet("openid", "profile", "email", "offline_access");
	private OAuth2RefreshTokenEntity refreshToken;
	private String refreshTokenValue = "refresh_token_value";
	private AuthorizationRequest authRequest;

	// for use when refreshing access tokens
	private AuthorizationRequest storedAuthRequest;
	private OAuth2Authentication storedAuthentication;
	private AuthenticationHolderEntity storedAuthHolder;
	private Set<String> storedScope;

	@Mock
	private OAuth2TokenRepository tokenRepository;

	@Mock
	private AuthenticationHolderRepository authenticationHolderRepository;

	@Mock
	private ClientDetailsEntityService clientDetailsService;

	@Mock
	private TokenEnhancer tokenEnhancer;

	@InjectMocks
	private DefaultOAuth2ProviderTokenService service;

	/**
	 * Set up a mock authentication and mock client to work with.
	 */
	@Before
	public void prepare() {
		Mockito.reset(tokenRepository, authenticationHolderRepository, clientDetailsService, tokenEnhancer);

		authentication = Mockito.mock(OAuth2Authentication.class);
		Mockito.when(authentication.getAuthorizationRequest()).thenReturn(Mockito.mock(AuthorizationRequest.class));
		AuthorizationRequest clientAuth = authentication.getAuthorizationRequest();

		client = Mockito.mock(ClientDetailsEntity.class);
		Mockito.when(client.getClientId()).thenReturn(clientId);
		Mockito.when(clientDetailsService.loadClientByClientId(clientId)).thenReturn(client);

		Mockito.when(clientAuth.getClientId()).thenReturn(clientId);
		Mockito.when(clientAuth.getScope()).thenReturn(scope);

		// by default in tests, allow refresh tokens
		Mockito.when(client.isAllowRefresh()).thenReturn(true);

		refreshToken = Mockito.mock(OAuth2RefreshTokenEntity.class);
		Mockito.when(tokenRepository.getRefreshTokenByValue(refreshTokenValue)).thenReturn(refreshToken);
		Mockito.when(refreshToken.getClient()).thenReturn(client);
		Mockito.when(refreshToken.isExpired()).thenReturn(false);

		authRequest = Mockito.mock(AuthorizationRequest.class);

		storedAuthRequest = Mockito.mock(AuthorizationRequest.class);
		storedAuthentication = Mockito.mock(OAuth2Authentication.class);
		storedAuthHolder = Mockito.mock(AuthenticationHolderEntity.class);
		storedScope = Sets.newHashSet(scope);

		Mockito.when(refreshToken.getAuthenticationHolder()).thenReturn(storedAuthHolder);
		Mockito.when(storedAuthHolder.getAuthentication()).thenReturn(storedAuthentication);
		Mockito.when(storedAuthentication.getAuthorizationRequest()).thenReturn(storedAuthRequest);
		Mockito.when(storedAuthRequest.getScope()).thenReturn(storedScope);
	}

	/**
	 * Tests exception handling for null authentication or null authorization.
	 */
	@Test
	public void createAccessToken_nullAuth() {

		Mockito.when(authentication.getAuthorizationRequest()).thenReturn(null);

		try {
			service.createAccessToken(null);
			fail("Authentication parameter is null. Excpected a AuthenticationCredentialsNotFoundException.");
		} catch (AuthenticationCredentialsNotFoundException e) {
			assertThat(e, is(notNullValue()));
		}

		try {
			service.createAccessToken(authentication);
			fail("AuthorizationRequest is null. Excpected a AuthenticationCredentialsNotFoundException.");
		} catch (AuthenticationCredentialsNotFoundException e) {
			assertThat(e, is(notNullValue()));
		}
	}

	/**
	 * Tests exception handling for clients not found.
	 */
	@Test(expected = InvalidClientException.class)
	public void createAccessToken_nullClient() {

		Mockito.when(clientDetailsService.loadClientByClientId(Mockito.anyString())).thenReturn(null);

		service.createAccessToken(authentication);
	}

	/**
	 * Tests the creation of access tokens for clients that are not allowed to have refresh tokens.
	 */
	@Test
	public void createAccessToken_noRefresh() {

		Mockito.when(client.isAllowRefresh()).thenReturn(false);

		OAuth2AccessTokenEntity token = service.createAccessToken(authentication);

		Mockito.verify(clientDetailsService).loadClientByClientId(Mockito.anyString());
		Mockito.verify(authenticationHolderRepository).save(Mockito.any(AuthenticationHolderEntity.class));
		Mockito.verify(tokenEnhancer).enhance(token, authentication);
		Mockito.verify(tokenRepository).saveAccessToken(token);

		Mockito.verify(tokenRepository, Mockito.never()).saveRefreshToken(Mockito.any(OAuth2RefreshTokenEntity.class));
		assertThat(token.getRefreshToken(), is(nullValue()));
	}

	/**
	 * Tests the creation of access tokens for clients that are allowed to have refresh tokens.
	 */
	@Test
	public void createAccessToken_yesRefresh() {

		AuthorizationRequest clientAuth = authentication.getAuthorizationRequest();
		Mockito.when(clientAuth.getScope()).thenReturn(Sets.newHashSet("offline_access"));
		Mockito.when(client.isAllowRefresh()).thenReturn(true);

		OAuth2AccessTokenEntity token = service.createAccessToken(authentication);

		// Note: a refactor may be appropriate to only save refresh tokens once to the repository during creation.
		Mockito.verify(tokenRepository, Mockito.atLeastOnce()).saveRefreshToken(Mockito.any(OAuth2RefreshTokenEntity.class));
		assertThat(token.getRefreshToken(), is(notNullValue()));

	}

	/**
	 * Checks to see that the expiration date of new tokens is being set accurately to within some delta for time skew.
	 */
	@Test
	public void createAccessToken_expiration() {

		Integer accessTokenValiditySeconds = 3600;
		Integer refreshTokenValiditySeconds = 600;

		Mockito.when(client.getAccessTokenValiditySeconds()).thenReturn(accessTokenValiditySeconds);
		Mockito.when(client.getRefreshTokenValiditySeconds()).thenReturn(refreshTokenValiditySeconds);

		long start = System.currentTimeMillis();
		OAuth2AccessTokenEntity token = service.createAccessToken(authentication);
		long end = System.currentTimeMillis();

		// Accounting for some delta for time skew on either side.
		Date lowerBoundAccessTokens = new Date(start + (accessTokenValiditySeconds * 1000L) - DELTA);
		Date upperBoundAccessTokens = new Date(end + (accessTokenValiditySeconds * 1000L) + DELTA);
		Date lowerBoundRefreshTokens = new Date(start + (refreshTokenValiditySeconds * 1000L) - DELTA);
		Date upperBoundRefreshTokens = new Date(end + (refreshTokenValiditySeconds * 1000L) + DELTA);

		assertTrue(token.getExpiration().after(lowerBoundAccessTokens) && token.getExpiration().before(upperBoundAccessTokens));
		assertTrue(token.getRefreshToken().getExpiration().after(lowerBoundRefreshTokens) && token.getRefreshToken().getExpiration().before(upperBoundRefreshTokens));
	}

	@Test
	public void createAccessToken_checkClient() {

		OAuth2AccessTokenEntity token = service.createAccessToken(authentication);

		assertThat(token.getClient().getClientId(), equalTo(clientId));
	}

	@Test
	public void createAccessToken_checkScopes() {

		OAuth2AccessTokenEntity token = service.createAccessToken(authentication);

		assertThat(token.getScope(), equalTo(scope));
	}

	@Test
	public void createAccessToken_checkAttachedAuthentication() {

		AuthenticationHolderEntity authHolder = Mockito.mock(AuthenticationHolderEntity.class);
		Mockito.when(authHolder.getAuthentication()).thenReturn(authentication);

		Mockito.when(authenticationHolderRepository.save(Mockito.any(AuthenticationHolderEntity.class))).thenReturn(authHolder);

		OAuth2AccessTokenEntity token = service.createAccessToken(authentication);

		assertThat(token.getAuthenticationHolder().getAuthentication(), equalTo(authentication));
		Mockito.verify(authenticationHolderRepository).save(Mockito.any(AuthenticationHolderEntity.class));
	}

	@Test(expected = InvalidTokenException.class)
	public void refreshAccessToken_noRefreshToken() {

		Mockito.when(tokenRepository.getRefreshTokenByValue(Mockito.anyString())).thenReturn(null);

		service.refreshAccessToken(refreshTokenValue, authRequest);
	}

	@Test(expected = InvalidClientException.class)
	public void refreshAccessToken_notAllowRefresh() {

		Mockito.when(client.isAllowRefresh()).thenReturn(false);

		service.refreshAccessToken(refreshTokenValue, authRequest);
	}

	@Test(expected = InvalidTokenException.class)
	public void refreshAccessToken_expired() {

		Mockito.when(refreshToken.isExpired()).thenReturn(true);

		service.refreshAccessToken(refreshTokenValue, authRequest);
	}

	@Test
	public void refreshAccessToken_verifyAcessToken() {

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		Mockito.verify(tokenRepository).clearAccessTokensForRefreshToken(refreshToken);

		assertThat(token.getClient(), equalTo(client));
		assertThat(token.getRefreshToken(), equalTo(refreshToken));
		assertThat(token.getAuthenticationHolder(), equalTo(storedAuthHolder));

		Mockito.verify(tokenEnhancer).enhance(token, storedAuthentication);
		Mockito.verify(tokenRepository).saveAccessToken(token);
	}

	@Test
	public void refreshAccessToken_requestingSameScope() {

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		assertThat(token.getScope(), equalTo(storedScope));
	}

	@Test
	public void refreshAccessToken_requestingLessScope() {

		Set<String> lessScope = Sets.newHashSet("openid", "profile");

		Mockito.when(authRequest.getScope()).thenReturn(lessScope);

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		assertThat(token.getScope(), equalTo(lessScope));
	}

	// Note: attempt at upscoping may throw an exception in future implementation.
	@Test
	public void refreshAccessToken_requestingMoreScope() {

		Set<String> moreScope = Sets.newHashSet(storedScope);
		moreScope.add("address");
		moreScope.add("phone");

		Mockito.when(authRequest.getScope()).thenReturn(moreScope);

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		assertThat(token.getScope(), not(equalTo(moreScope)));
		assertThat(token.getScope(), equalTo(storedScope));
	}

	/**
	 * Tests the case where only some of the valid scope values are being requested along with 
	 * other extra unauthorized scope values.
	 */
	@Test
	public void refreshAccessToken_requestingMixedScope() {

		Set<String> mixedScope = Sets.newHashSet("openid", "profile", "address", "phone"); // no email or offline_access

		Mockito.when(authRequest.getScope()).thenReturn(mixedScope);

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		// Current behavior is to simply return the set scope values stored in the initial authorization.
		assertThat(token.getScope(), equalTo(storedScope));
	}

	@Test
	public void refreshAccessToken_requestingEmptyScope() {

		Set<String> emptyScope = Sets.newHashSet();

		Mockito.when(authRequest.getScope()).thenReturn(emptyScope);

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		assertThat(token.getScope(), equalTo(storedScope));
	}

	@Test
	public void refreshAccessToken_requestingNullScope() {

		Mockito.when(authRequest.getScope()).thenReturn(null);

		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);

		assertThat(token.getScope(), equalTo(storedScope));

	}

	/**
	 * Checks to see that the expiration date of refreshed tokens is being set accurately to within some delta for time skew.
	 */
	@Test
	public void refreshAccessToken_expiration() {

		Integer accessTokenValiditySeconds = 3600;

		Mockito.when(client.getAccessTokenValiditySeconds()).thenReturn(accessTokenValiditySeconds);

		long start = System.currentTimeMillis();
		OAuth2AccessTokenEntity token = service.refreshAccessToken(refreshTokenValue, authRequest);
		long end = System.currentTimeMillis();

		// Accounting for some delta for time skew on either side.
		Date lowerBoundAccessTokens = new Date(start + (accessTokenValiditySeconds * 1000L) - DELTA);
		Date upperBoundAccessTokens = new Date(end + (accessTokenValiditySeconds * 1000L) + DELTA);

		assertTrue(token.getExpiration().after(lowerBoundAccessTokens) && token.getExpiration().before(upperBoundAccessTokens));
	}

}
