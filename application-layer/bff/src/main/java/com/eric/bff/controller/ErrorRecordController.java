package com.eric.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/errors")
public class ErrorRecordController {
    

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public ErrorRecordController(RestClient restClient) {
        this.restClient = restClient;
    }


    @GetMapping
    public ResponseEntity<String> getAllErrors(HttpServletRequest request) {
        return restClient.get()
        .uri(govApiUrl + "/error" + queryString(request))
        .retrieve()
        .toEntity(String.class);
    }


    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }

}
