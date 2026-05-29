package com.eric.governanceApi.governanceApi.config;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cliente REST que encapsula toda comunicação com o Agent.
 * Qualquer service que precise publicar no broker usa este client.
 */
@Slf4j
@Component
public class AgentClient {

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.url}")
    private String agentBaseUrl;

    @Value("${INFRA_API_KEY}")
    private String infraApiKey;

    public AgentClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Envia um broadcast ao Agent para publicar em commands/<MAC>/<subtopic>.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> broadcastCommands(DeviceCommands command, Map<String, Object> payload, List<String> targetMacs) {
        Map<String, Object> request = Map.of(
            "command",    command,
            "payload",    payload,
            "targetMacs", targetMacs
        );

        log.info("Enviando payload {} para os macs {}", payload, targetMacs);

        try {
            String body = restClient.post()
                .uri(agentBaseUrl + "/agent/broadcast")
                .header("Content-Type", "application/json")
                .header("api-key", infraApiKey)
                .body(request)
                .retrieve()
                .body(String.class);

            Map<String, Object> response = mapper.readValue(body, Map.class);
            log.info("Agent respondeu: {}", body);
            return response;

        } catch (ResourceAccessException e) {
            log.error("Agent offline: {}", e.getMessage());
            throw new InfrastructureException("Agent offline. Comando cancelado.");
        } catch (Exception e) {
            log.error("Falha ao comunicar com Agent: {}", e.getMessage());
            throw new InfrastructureException("Falha na comunicação com o Agent: " + e.getMessage());
        }
    }

    // public ResponseEntity<Void> broadcastProvisioning() {
    //     try {
    //         String body = restClient.post()
    //         .uri(agentBaseUrl + "/agent/provisioning")
    //         .header("Content-Type", "application/json")
    //         .header("api-key", infraApiKey)
    //         .body(request)
    //         .retrieve()
    //         .body(String.class);

    //         Map<String, Object> response = mapper.readValue(body, Map.class);
    //         log.info("Agent respondeu: {}", body);
    //     } catch (ResourceAccessException e) {
    //         log.error("Agent offline: {}", e.getMessage());
    //         throw new InfrastructureException("Agent offline. Comando cancelado.");
    //     } catch (Exception e) {
    //         log.error("Falha ao comunicar com Agent: {}", e.getMessage());
    //         throw new InfrastructureException("Falha na comunicação com o Agent: " + e.getMessage());
    //     }
    // }
}