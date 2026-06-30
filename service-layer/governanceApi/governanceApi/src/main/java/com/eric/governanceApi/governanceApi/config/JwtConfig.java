package com.eric.governanceApi.governanceApi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.List;
import java.util.Map;

@Configuration
public class JwtConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> defaultValidators = JwtValidators.createDefaultWithIssuer(issuerUri);

        // Rejeita ID tokens — apenas access tokens (typ=Bearer) são aceitos
        OAuth2TokenValidator<Jwt> bearerTypeValidator = jwt -> {
            String typ = jwt.getClaimAsString("typ");
            if ("Bearer".equals(typ)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Token rejeitado: tipo '" + typ + "' inválido. Apenas access tokens são aceitos.",
                null
            ));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidators, bearerTypeValidator));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> allRoles = new java.util.ArrayList<>();

            // Realm roles
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                allRoles.addAll(roles);
            }

            // Client roles (resource_access.<client>.roles)
            @SuppressWarnings("unchecked")
            Map<String, Object> resourceAccess = (Map<String, Object>) jwt.getClaim("resource_access");
            if (resourceAccess != null) {
                resourceAccess.values().forEach(v -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> clientAccess = (Map<String, Object>) v;
                    if (clientAccess != null && clientAccess.containsKey("roles")) {
                        @SuppressWarnings("unchecked")
                        List<String> clientRoles = (List<String>) clientAccess.get("roles");
                        allRoles.addAll(clientRoles);
                    }
                });
            }

            return allRoles.stream()
                    .map(role -> role.toUpperCase().startsWith("ROLE_") ? role.toUpperCase() : "ROLE_" + role.toUpperCase())
                    .distinct()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                    .toList();
        });
        return converter;
    }
}
