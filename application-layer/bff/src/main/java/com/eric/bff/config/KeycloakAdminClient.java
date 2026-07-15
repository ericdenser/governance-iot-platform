package com.eric.bff.config;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class KeycloakAdminClient {

    @Value("${spring.security.oauth2.client.registration.keycloak.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.keycloak.client-secret}")
    private String clientSecret;

    @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
    private String issuerUri;

    @Value("${app.keycloak-admin-url}")
    private String adminUrl;

    private final RestClient restClient = RestClient.create();

    public List<Map<String, Object>> listUsers() {
        String token = fetchClientCredentialsToken();
        return restClient.get()
                .uri(adminUrl + "/users?max=500&briefRepresentation=false")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public Map<String, Object> getUser(String userId) {
        String token = fetchClientCredentialsToken();
        return restClient.get()
                .uri(adminUrl + "/users/" + userId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** Cria user no Keycloak. Retorna o ID gerado  */
    public String createUser(Map<String, Object> representation) {
        String token = fetchClientCredentialsToken();
        ResponseEntity<Void> response = restClient.post()
                .uri(adminUrl + "/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(representation)
                .retrieve()
                .toBodilessEntity();
        URI location = response.getHeaders().getLocation();
        if (location == null) return null;
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public void updateUser(String userId, Map<String, Object> representation) {
        String token = fetchClientCredentialsToken();
        restClient.put()
                .uri(adminUrl + "/users/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(representation)
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteUser(String userId) {
        String token = fetchClientCredentialsToken();
        restClient.delete()
                .uri(adminUrl + "/users/" + userId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    public void resetPassword(String userId, String newPassword, boolean temporary) {
        String token = fetchClientCredentialsToken();
        Map<String, Object> credential = Map.of(
                "type", "password",
                "value", newPassword,
                "temporary", temporary
        );
        restClient.put()
                .uri(adminUrl + "/users/" + userId + "/reset-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(credential)
                .retrieve()
                .toBodilessEntity();
    }

    private String fetchClientCredentialsToken() {
        String tokenEndpoint = issuerUri + "/protocol/openid-connect/token";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");

        Map<String, Object> response = restClient.post()
                .uri(tokenEndpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        return (String) response.get("access_token");
    }
}
