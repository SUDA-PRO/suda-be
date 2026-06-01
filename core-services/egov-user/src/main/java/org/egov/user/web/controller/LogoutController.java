package org.egov.user.web.controller;

import org.egov.common.contract.response.Error;
import org.egov.common.contract.response.ErrorResponse;
import org.egov.common.contract.response.ResponseInfo;
import org.egov.user.domain.model.TokenWrapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.bind.annotation.*;

// REMOVED DEPRECATED IMPORTS:
// import org.springframework.security.oauth2.core.OAuth2AccessToken;
// import org.springframework.security.oauth2.provider.token.TokenStore;

import java.util.Date;

@RestController
public class LogoutController {

    private static final String ACCESS_TOKEN_KEY_PREFIX = "access_token:";
    private static final String REFRESH_TOKEN_KEY_PREFIX = "refresh_token:";
    private static final String AUTHORIZATION_KEY_PREFIX = "oauth2:authorization:";

    // CHANGED: TokenStore -> OAuth2AuthorizationService
    private OAuth2AuthorizationService authorizationService;
    private RedisTemplate<String, Object> redisTemplate;

    // UPDATED CONSTRUCTOR
    public LogoutController(OAuth2AuthorizationService authorizationService,
                            RedisTemplate<String, Object> redisTemplate) {
        this.authorizationService = authorizationService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * End-point to logout the session.
     * Updated to work with OAuth2AuthorizationService
     *
     * @param tokenWrapper containing the access token
     * @return ResponseInfo indicating success or failure
     * @throws Exception
     */
    @PostMapping("/_logout")
    public ResponseInfo deleteToken(@RequestBody TokenWrapper tokenWrapper) throws Exception {
        String accessToken = normalizeAccessToken(tokenWrapper.getAccessToken());
        
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Access token is required");
        }
        
        // UPDATED: Find authorization by access token
        OAuth2Authorization authorization = authorizationService.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        
        if (authorization != null) {
            // UPDATED: Remove the entire authorization (which includes all associated tokens)
            authorizationService.remove(authorization);
            return new ResponseInfo("", "", System.currentTimeMillis(), "", "", "Logout successfully");
        } else {
            // Fallback for opaque tokens stored directly in Redis metadata map.
            if (revokeOpaqueToken(accessToken)) {
                return new ResponseInfo("", "", System.currentTimeMillis(), "", "", "Logout successfully");
            }

            // Token not found or already expired
            return new ResponseInfo("", "", System.currentTimeMillis(), "", "", "Token not found or already expired");
        }
    }

    private String normalizeAccessToken(String accessToken) {
        if (accessToken == null) {
            return null;
        }

        String token = accessToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return token.substring(7).trim();
        }
        return token;
    }

    private boolean revokeOpaqueToken(String accessToken) {
        String accessTokenKey = ACCESS_TOKEN_KEY_PREFIX + accessToken;
        Object tokenValue = redisTemplate.opsForValue().get(accessTokenKey);

        if (tokenValue == null) {
            return false;
        }

        // If this is an authorization-id index, remove parent authorization as well.
        if (tokenValue instanceof String) {
            String authorizationId = (String) tokenValue;
            redisTemplate.delete(AUTHORIZATION_KEY_PREFIX + authorizationId);
        }

        redisTemplate.delete(accessTokenKey);
        redisTemplate.delete(REFRESH_TOKEN_KEY_PREFIX + accessToken);
        return true;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleError(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(LogoutController.class)
                .error("Logout failed", ex);
        ErrorResponse response = new ErrorResponse();
        ResponseInfo responseInfo = new ResponseInfo("", "", System.currentTimeMillis(), "", "", "Logout failed");
        response.setResponseInfo(responseInfo);
        Error error = new Error();
        error.setCode(400);
        error.setDescription("Logout failed: " + ex.getMessage());
        response.setError(error);
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
}
