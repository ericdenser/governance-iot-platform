package com.eric.agentmqtt.service;

import com.eric.agentmqtt.model.TelemetryDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class TelemetryForwardingService {

    private final RestClient restClient;

    @Value("${datalogger.url}")
    private String dataloggerUrl;

    public TelemetryForwardingService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void forwardTelemetryToDataLogger(TelemetryDTO dto) {
        log.info("Encaminhando telemetria ao DataLogger: device={}, readings={}",
                dto.deviceId(), dto.readings().keySet());
        try {
            restClient.post()
                    .uri(dataloggerUrl + "/datalogger/ingest")
                    .body(dto)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Falha ao encaminhar telemetria: {}", e.getMessage());
        }
    }
}
