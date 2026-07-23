package com.eric.governanceApi.governanceApi.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.model.response.CreatedKeycloakClient;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class KeycloakDeviceClientService {
    
    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    @Value("${keycloak.url}")
    private String keycloakAdminUrl;


    public KeycloakDeviceClientService(RestClient restClient, OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = restClient;
        this.authorizedClientManager = authorizedClientManager;
    }


    public CreatedKeycloakClient createClient(String deviceUuid) {
        String adminToken = fetchAdminToken();
        String location; // contem o internalId

        // POST para criar o client
        try {
            location = restClient.post()
                    .uri(keycloakAdminUrl + "/clients")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                        "clientId", deviceUuid,
                        "protocol", "openid-connect",
                        "publicClient", false,
                        "serviceAccountsEnabled", true,
                        "standardFlowEnabled", false,
                        "directAccessGrantsEnabled", false,
                        "attributes", Map.of("access.token.lifespan", "3600")                   
                    ))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getFirst(HttpHeaders.LOCATION);
        } catch (HttpClientErrorException e) {
            throw new InfrastructureException(ErrorCode.KEYCLOAK_CLIENT_ERROR,
                "Keycloack createClient failed (" + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }

        if (location == null ||location.isBlank()) {
            throw new InfrastructureException(ErrorCode.KEYCLOAK_CLIENT_ERROR, "Keycloak did not return Location header in POST /clients");
        }
        String internalId = location.substring(location.lastIndexOf('/') + 1);

        // GET no client para buscar a secret 
        @SuppressWarnings("rawtypes")
        Map resp = restClient.get()
                .uri(keycloakAdminUrl + "/clients/{id}/client-secret", internalId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .body(Map.class);

        if (resp == null || resp.get("value") == null) {
            throw new InfrastructureException(ErrorCode.KEYCLOAK_CLIENT_ERROR, "Keycloak returned empty client-secret for internalId=" + internalId);
        }

        String secret = resp.get("value").toString();

        log.info("Keycloak client created: deviceUuid={} internalId={}", deviceUuid, internalId);
        return new CreatedKeycloakClient(internalId, deviceUuid, secret);
    }

    public void deleteClient(String internalId) {
        if (internalId == null || internalId.isBlank()) {
            log.warn("deleteClient called for empty internalId, ignoring.");
            return;
        }

        String adminToken = fetchAdminToken();

        try {
            restClient.delete()
                .uri(keycloakAdminUrl + "/clients/{id}", internalId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .toBodilessEntity();
            
                log.info("Keycloak client deleted: internalId={}", internalId);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("DELETE Keycloak client {} failed, it dont exist", internalId);
                return;
            }
            throw new InfrastructureException(ErrorCode.KEYCLOAK_CLIENT_ERROR, "Keycloak deleteClient failed (" + e.getStatusCode() + "): "
                                            + e.getResponseBodyAsString(), e);
        }
    }


    private String fetchAdminToken() {
        OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
            .withClientRegistrationId("gov-api")
            .principal("gov-api")
            .build();

        OAuth2AuthorizedClient client = authorizedClientManager.authorize(req);
        if (client == null) {
            throw new InfrastructureException(ErrorCode.KEYCLOAK_CLIENT_ERROR, "Failed authorizing gov-api (verify client_secret and credentials)");
        }

        return client.getAccessToken().getTokenValue();
    }
}