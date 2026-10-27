package com.eric.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

// Serie temporal do datalogger (Influx). O datalogger nao tem auth nem escopo
// por grupo, entao o acesso ao device e conferido no govApi antes.
@RestController
@RequestMapping("/telemetry")
public class TelemetryController {

    private final RestClient restClient;
    // sem token relay: o datalogger e interno e nao valida JWT
    private final RestClient dataloggerClient = RestClient.create();

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    @Value("${app.datalogger-url:http://datalogger:8081}")
    private String dataloggerUrl;

    public TelemetryController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<String> getSeries(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "60") int minutes,
            @RequestParam(defaultValue = "1m") String resolution) {
        checkAccess(deviceId);
        return dataloggerClient.get()
                .uri(dataloggerUrl + "/datalogger/telemetry/{id}?minutes={m}&resolution={r}", deviceId, minutes, resolution)
                .retrieve()
                .toEntity(String.class);
    }

    // 403/404 do govApi sobe pelo GlobalExceptionHandler
    private void checkAccess(String deviceId) {
        restClient.get()
                .uri(govApiUrl + "/devices/{id}", deviceId)
                .retrieve()
                .toBodilessEntity();
    }
}
