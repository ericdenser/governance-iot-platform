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
            "username", principal.getPreferredUsername(),
            "keycloakUserId", principal.getSubject()
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

    @SuppressWarnings("unchecked")
    private boolean hasRoleInJwt(OAuth2AuthorizedClient client, List<String> requiredRoles) {
        try {
            String accessTokenStr = client.getAccessToken().getTokenValue();
            SignedJWT jwt = SignedJWT.parse(accessTokenStr);
            var claims = jwt.getJWTClaimsSet();

            List<String> allRoles = new java.util.ArrayList<>();

            // Realm roles
            Map<String, Object> realmAccess = (Map<String, Object>) claims.getClaim("realm_access");
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                allRoles.addAll((List<String>) realmAccess.get("roles"));
            }

            // Client roles (resource_access.<client>.roles)

            Map<String, Object> resourceAccess = (Map<String, Object>) claims.getClaim("resource_access");
            if (resourceAccess != null) {
                resourceAccess.values().forEach(v -> {
       
                    Map<String, Object> clientAccess = (Map<String, Object>) v;
                    if (clientAccess != null && clientAccess.containsKey("roles")) {
     
                        List<String> clientRoles = (List<String>) clientAccess.get("roles");
                        allRoles.addAll(clientRoles);
                    }
                });
            }

            List<String> formattedRoles = allRoles.stream()
                    .map(role -> role.toUpperCase().startsWith("ROLE_") ? role.toUpperCase() : "ROLE_" + role.toUpperCase())
                    .distinct()
                    .toList();

            logger.info("Roles do token: {} | Roles exigidas: {}", formattedRoles, requiredRoles);

            return formattedRoles.stream().anyMatch(requiredRoles::contains);
        } catch (Exception e) {
            logger.error("Erro ao verificar roles no JWT: {}", e.getMessage());
        }
        return false;
    }
}
