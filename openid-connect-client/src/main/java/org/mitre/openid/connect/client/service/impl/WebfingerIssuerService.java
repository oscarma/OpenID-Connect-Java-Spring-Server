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
/**
 * 
 */
package org.mitre.openid.connect.client.service.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.client.HttpClient;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.DefaultHttpClient;
import org.mitre.openid.connect.client.model.IssuerServiceResponse;
import org.mitre.openid.connect.client.service.IssuerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Use Webfinger to discover the appropriate issuer for a user-given input string.
 * @author jricher
 *
 */
public class WebfingerIssuerService implements IssuerService {

	private static Logger logger = LoggerFactory.getLogger(WebfingerIssuerService.class);

	// pattern used to parse user input; we can't use the built-in java URI parser
	private static final Pattern pattern = Pattern.compile("^((https|acct|http|mailto):(//)?)?((([^@]+)@)?(([^:]+)(:(\\d*))?))([^\\?]+)?(\\?([^#]+))?(#(.*))?$");

	// map of user input -> issuer, loaded dynamically from webfinger discover
	private LoadingCache<UriComponents, String> issuers;

	private Set<String> whitelist = new HashSet<String>();
	private Set<String> blacklist = new HashSet<String>();

	/**
	 * Name of the incoming parameter to check for discovery purposes.
	 */
	private String parameterName = "identifier";

	/**
	 * URL of the page to forward to if no identifier is given.
	 */
	private String loginPageUrl;

	public WebfingerIssuerService() {
		issuers = CacheBuilder.newBuilder().build(new WebfingerIssuerFetcher());
	}

	/* (non-Javadoc)
	 * @see org.mitre.openid.connect.client.service.IssuerService#getIssuer(javax.servlet.http.HttpServletRequest)
	 */
	@Override
	public IssuerServiceResponse getIssuer(HttpServletRequest request) {

		String identifier = request.getParameter(parameterName);
		if (!Strings.isNullOrEmpty(identifier)) {
			try {
				String issuer = issuers.get(normalizeResource(identifier));
				if (!whitelist.isEmpty() && !whitelist.contains(issuer)) {
					throw new AuthenticationServiceException("Whitelist was nonempty, issuer was not in whitelist: " + issuer);
				}
				
				if (blacklist.contains(issuer)) {
					throw new AuthenticationServiceException("Issuer was in blacklist: " + issuer);
				}
				
				return new IssuerServiceResponse(issuer, null, null);
			} catch (ExecutionException e) {
				logger.warn("Issue fetching issuer for user input: " + identifier, e);
				return null;
			}

		} else {
			logger.warn("No user input given, directing to login page: " + loginPageUrl);
			return new IssuerServiceResponse(loginPageUrl);
		}
	}

	/**
	 * Normalize the resource string as per OIDC Discovery.
	 * @param identifier
	 * @return the normalized string, or null if the string can't be normalized
	 */
	private UriComponents normalizeResource(String identifier) {
		// try to parse the URI
		// NOTE: we can't use the Java built-in URI class because it doesn't split the parts appropriately

		if (Strings.isNullOrEmpty(identifier)) {
			logger.warn("Can't normalize null or empty URI: " + identifier);
			return null; // nothing we can do
		} else {

			//UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(identifier);
			UriComponentsBuilder builder = UriComponentsBuilder.newInstance();

			//Pattern regex = Pattern.compile("^(([^:/?#]+):)?(//(([^@/]*)@)?([^/?#:]*)(:(\\d*))?)?([^?#]*)(\\?([^#]*))?(#(.*))?");
			Matcher m = pattern.matcher(identifier);
			if (m.matches()) {
				builder.scheme(m.group(2));
				builder.userInfo(m.group(6));
				builder.host(m.group(8));
				String port = m.group(10);
				if (!Strings.isNullOrEmpty(port)) {
					builder.port(Integer.parseInt(port));
				}
				builder.path(m.group(11));
				builder.query(m.group(13));
				builder.fragment(m.group(15)); // we throw away the hash, but this is the group it would be if we kept it
			} else {
				// doesn't match the pattern, throw it out
				logger.warn("Parser couldn't match input: " + identifier);
				return null;
			}
			
			UriComponents n = builder.build();
			
			if (Strings.isNullOrEmpty(n.getScheme())) {
				if (!Strings.isNullOrEmpty(n.getUserInfo())
						&& Strings.isNullOrEmpty(n.getPath())
						&& Strings.isNullOrEmpty(n.getQuery())
						&& n.getPort() < 0) {
					
					// scheme empty, userinfo is not empty, path/query/port are empty
					// set to "acct" (rule 2)
					builder.scheme("acct");
					
				} else {
					// scheme is empty, but rule 2 doesn't apply
					// set scheme to "https" (rule 3)
					builder.scheme("https");
				}
			}
			
			// fragment must be stripped (rule 4)
			builder.fragment(null);
			
			return builder.build();
		}


	}


	/**
	 * @return the parameterName
	 */
	public String getParameterName() {
		return parameterName;
	}

	/**
	 * @param parameterName the parameterName to set
	 */
	public void setParameterName(String parameterName) {
		this.parameterName = parameterName;
	}


	/**
	 * @return the loginPageUrl
	 */
	public String getLoginPageUrl() {
		return loginPageUrl;
	}

	/**
	 * @param loginPageUrl the loginPageUrl to set
	 */
	public void setLoginPageUrl(String loginPageUrl) {
		this.loginPageUrl = loginPageUrl;
	}

	/**
	 * @return the whitelist
	 */
	public Set<String> getWhitelist() {
		return whitelist;
	}

	/**
	 * @param whitelist the whitelist to set
	 */
	public void setWhitelist(Set<String> whitelist) {
		this.whitelist = whitelist;
	}

	/**
	 * @return the blacklist
	 */
	public Set<String> getBlacklist() {
		return blacklist;
	}

	/**
	 * @param blacklist the blacklist to set
	 */
	public void setBlacklist(Set<String> blacklist) {
		this.blacklist = blacklist;
	}

	/**
	 * @author jricher
	 *
	 */
	private class WebfingerIssuerFetcher extends CacheLoader<UriComponents, String> {
		private HttpClient httpClient = new DefaultHttpClient();
		private HttpComponentsClientHttpRequestFactory httpFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		private JsonParser parser = new JsonParser();

		@Override
		public String load(UriComponents key) throws Exception {

			RestTemplate restTemplate = new RestTemplate(httpFactory);
			// construct the URL to go to
			
			// preserving http scheme is strictly for demo system use only.
			String scheme = key.getScheme();
			if (!Strings.isNullOrEmpty(scheme) && scheme.equals("http")) {
				scheme = "http://"; // add on colon and slashes.
				logger.warn("Webfinger endpoint MUST use the https URI scheme.");
			} else {
				scheme = "https://";
			}

			// do a webfinger lookup
			URIBuilder builder = new URIBuilder(scheme 
												+ key.getHost()
												+ (key.getPort() >= 0 ? ":" + key.getPort() : "")
												+ Strings.nullToEmpty(key.getPath())
												+ "/.well-known/webfinger" 
												+ (Strings.isNullOrEmpty(key.getQuery()) ? "" : "?" + key.getQuery())
												);
			builder.addParameter("resource", key.toString());
			builder.addParameter("rel", "http://openid.net/specs/connect/1.0/issuer");

			// do the fetch
			logger.info("Loading: " + builder.toString());
			String webfingerResponse = restTemplate.getForObject(builder.build(), String.class);

			// TODO: catch and handle HTTP errors

			JsonElement json = parser.parse(webfingerResponse);

			// TODO: catch and handle JSON errors

			if (json != null && json.isJsonObject()) {
				// find the issuer
				JsonArray links = json.getAsJsonObject().get("links").getAsJsonArray();
				for (JsonElement link : links) {
					if (link.isJsonObject()) {
						JsonObject linkObj = link.getAsJsonObject();
						if (linkObj.has("href")
								&& linkObj.has("rel")
								&& linkObj.get("rel").getAsString().equals("http://openid.net/specs/connect/1.0/issuer")) {

							// we found the issuer, return it
							return linkObj.get("href").getAsString();
						}
					}
				}
			}

			// we couldn't find it
			
			if (key.getScheme().equals("http") || key.getScheme().equals("https")) {
				// if it looks like HTTP then punt and return the input
				logger.warn("Returning normalized input string as issuer, hoping for the best: " + key.toString());
				return key.toString();
			} else {
				// if it's not HTTP, give up
				logger.warn("Couldn't find issuer: " + key.toString());
				return null;
			}
			
		}

	}

}
