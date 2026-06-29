package com.eric.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/sensors")
public class SensorController {

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public SensorController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping
    public ResponseEntity<String> listAll() {
        return restClient.get()
                .uri(govApiUrl + "/sensors")
                .retrieve()
                .toEntity(String.class);
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody String body) {
        return restClient.post()
                .uri(govApiUrl + "/sensors/register")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }
}
