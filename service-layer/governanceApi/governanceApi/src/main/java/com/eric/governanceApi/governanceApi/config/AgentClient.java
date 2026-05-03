package com.eric.governanceApi.governanceApi.config;

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
 * Cliente REST que encapsula toda comunicação com o Agent MQTT.
 * Qualquer service que precise publicar no broker usa este client,
 * eliminando duplicação de lógica HTTP + tratamento de erro.
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
     *
     * @param subtopic  ex: "ota", "reboot", "sleep"
     * @param payload   JSON que o ESP vai receber (já serializado)
     * @param targetMacs lista de MACs destino
     * @return resposta do Agent com publishedTo e failed
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> broadcast(String subtopic, String payload, List<String> targetMacs) {
        Map<String, Object> request = Map.of(
            "subtopic",   subtopic,
            "payload",    payload,
            "targetMacs", targetMacs
        );

        try {
            String body = restClient.post()
                .uri(agentBaseUrl + "/agent/broadcast")
                .header("Content-Type", "application/json")
                .header("api-key", infraApiKey)
                .body(request)
                .retrieve()
                .body(String.class);

            Map<String, Object> response = mapper.readValue(body, Map.class);
            log.info("Agent respondeu para [{}]: {}", subtopic, body);
            return response;

        } catch (ResourceAccessException e) {
            log.error("Agent offline: {}", e.getMessage());
            throw new InfrastructureException("Agent offline. Comando cancelado.");
        } catch (Exception e) {
            log.error("Falha ao comunicar com Agent: {}", e.getMessage());
            throw new InfrastructureException("Falha na comunicação com o Agent: " + e.getMessage());
        }
    }
}