package com.eric.bff.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.nimbusds.jwt.SignedJWT;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final OAuth2AuthorizedClientManager clientManager;

    public UserService(OAuth2AuthorizedClientManager clientManager) {
        this.clientManager = clientManager;
    }

    private OAuth2AuthorizedClient getAuthorizedClient(
            OAuth2AuthenticationToken authToken,
            HttpServletRequest request,
            HttpServletResponse response) {

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(authToken.getAuthorizedClientRegistrationId())
                .principal(authToken)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();

        return clientManager.authorize(authorizeRequest);
    }

    public Map<String, Object> getAuthenticatedUserInfo(
            OAuth2AuthenticationToken authToken,
            OidcUser principal,
            HttpServletRequest request,
            HttpServletResponse response) {

        OAuth2AuthorizedClient client = getAuthorizedClient(authToken, request, response);

        if (client == null || client.getAccessToken() == null) {
            logger.warn("Tokens não encontrados no Redis para o usuário {}", authToken.getName());
            return null;
        }

        logger.info("GET /me — usuário: {}", principal.getPreferredUsername());

        return Map.of(
            "authenticated", true,
            "nome", principal.getFullName(),
            "username", principal.getPreferredUsername()
        );
    }

    public boolean checkUserHasRole(
            List<String> requiredRoles,
            OAuth2AuthenticationToken authToken,
            HttpServletRequest request,
            HttpServletResponse response) {

        OAuth2AuthorizedClient client = getAuthorizedClient(authToken, request, response);

        if (client == null || client.getAccessToken() == null) {
            return false;
        }

        return hasRoleInJwt(client, requiredRoles);
    }

    private boolean hasRoleInJwt(OAuth2AuthorizedClient client, List<String> requiredRoles) {
        try {
            String accessTokenStr = client.getAccessToken().getTokenValue();
            SignedJWT jwt = SignedJWT.parse(accessTokenStr);

            @SuppressWarnings("unchecked")
            Map<String, Object> realmAccess = (Map<String, Object>) jwt.getJWTClaimsSet().getClaim("realm_access");

            if (realmAccess != null && realmAccess.containsKey("roles")) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");

                List<String> formattedRoles = roles.stream()
                        .map(role -> "ROLE_" + role.toUpperCase())
                        .toList();

                logger.info("Roles do token: {} | Roles exigidas: {}", formattedRoles, requiredRoles);

                return formattedRoles.stream().anyMatch(requiredRoles::contains);
            }
        } catch (Exception e) {
            logger.error("Erro ao verificar roles no JWT: {}", e.getMessage());
        }
        return false;
    }
}
