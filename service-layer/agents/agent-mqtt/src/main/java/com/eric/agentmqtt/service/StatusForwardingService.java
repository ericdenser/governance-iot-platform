package com.eric.agentmqtt.service;

import com.eric.agentmqtt.model.StatusDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class StatusForwardingService {

    private final RestClient restClient;

    @Value("${event-handler.url}")
    private String eventHandlerUrl;

    public StatusForwardingService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void fowardStatusToEventHandler(StatusDTO dto) {
        log.info("Encaminhando status ao event-handler: {}", dto);
        try {
            restClient.post()
                .uri(eventHandlerUrl + "/events/ingest")
                .body(dto)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.error("Falha ao encaminhar status: {}", e.getMessage());
        }
    }
}
