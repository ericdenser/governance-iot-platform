package com.eric.agentmqtt.service;

import com.eric.agentmqtt.model.ErrorDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class ErrorForwardingService {

    private final RestClient restClient;

    public ErrorForwardingService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void fowardErrorToEventHandler(ErrorDTO dto) {
        log.info("Encaminhando status ao event-handler: {}", dto);
        try {
            restClient.post()
                .uri("http://localhost:8082/error/ingest")
                .body(dto)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.error("Falha ao encaminhar status: {}", e.getMessage());
        }
    }
}
