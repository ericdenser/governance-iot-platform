package com.eric.agentmqtt.service;

import com.eric.agentmqtt.model.ErrorDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class ErrorForwardingService {

    private final RestClient restClient;

    @Value("${govapi.url}")
    private String govApiUrl;

    public ErrorForwardingService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void fowardErrorToEventHandler(ErrorDTO dto) {
        log.info("Encaminhando erro para govApi: {}", dto);
        try {
            restClient.post()
                .uri(govApiUrl + "/error/ingest")
                .body(dto)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.error("Falha ao encaminhar erro: {}", e.getMessage());
        }
    }
}
