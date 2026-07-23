package com.eric.governanceApi.governanceApi.model.response;

public record CreatedKeycloakClient(
    String internalId,
    String clientId,
    String clientSecret
) {
    
}
