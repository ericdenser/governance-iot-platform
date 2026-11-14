package com.eric.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/audit")
public class AuditController {

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public AuditController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping
    public ResponseEntity<String> list(HttpServletRequest request) {
        return restClient.get()
                .uri(URI.create(govApiUrl + "/audit" + queryString(request))) // already encoded by the browser, no re-encoding
                .retrieve()
                .toEntity(String.class);
    }

    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }
}
