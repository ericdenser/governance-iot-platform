package com.eric.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/commands")
public class CommandController {

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public CommandController(RestClient restClient) {
        this.restClient = restClient;
    }

    @PostMapping("/send")
    public ResponseEntity<String> sendCommand(@RequestBody String body) {
        return restClient.post()
                .uri(govApiUrl + "/commands/send")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping
    public ResponseEntity<String> getAllCommands(HttpServletRequest request) {
        return restClient.get()
                .uri(govApiUrl + "/commands" + queryString(request))
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<String> getBatchRecords(@PathVariable String batchId) {
        return restClient.get()
                .uri(govApiUrl + "/commands/{batchId}", batchId)
                .retrieve()
                .toEntity(String.class);
    }

    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }
}
