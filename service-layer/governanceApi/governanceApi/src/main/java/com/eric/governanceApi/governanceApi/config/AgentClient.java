package com.eric.governanceApi.governanceApi.config;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.model.response.AgentBroadcastResultDTO;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AgentClient {

    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    @Value("${agent.url}")
    private String agentBaseUrl;

    public AgentClient(RestClient restClient, OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = restClient;
        this.authorizedClientManager = authorizedClientManager;
    }

    private String fetchToken() {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId("gov-api")
                .principal("gov-api")
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new InfrastructureException(ErrorCode.AGENT_UNREACHABLE,
                "Não foi possível obter token OAuth2 para o agent-mqtt.");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }

    public AgentBroadcastResultDTO broadcastCommands(DeviceCommands command, Map<String, Object> payload, List<String> targetDevices) {
        Map<String, Object> request = Map.of(
            "command",       command,
            "payload",       payload,
            "targetDevices", targetDevices
        );

        log.info("Enviando payload {} para os devices {}", payload, targetDevices);

        try {
            AgentBroadcastResultDTO response = restClient.post()
                    .uri(agentBaseUrl + "/agent/broadcast")
                    .header("Authorization", "Bearer " + fetchToken())
                    .body(request)
                    .retrieve()
                    .body(AgentBroadcastResultDTO.class);

            log.info("Agent respondeu: {}", response);
            return response;

        } catch (ResourceAccessException e) {
            log.error("Agent offline: {}", e.getMessage());
            throw new InfrastructureException(ErrorCode.AGENT_UNREACHABLE,
                "Agent offline. Comando cancelado.");
        } catch (InfrastructureException e) {
            throw e;
        } catch (Exception e) {
            log.error("Falha ao comunicar com Agent: {}", e.getMessage());
            throw new InfrastructureException(ErrorCode.AGENT_UNREACHABLE,
                "Falha na comunicação com o Agent: " + e.getMessage());
        }
    }
}
