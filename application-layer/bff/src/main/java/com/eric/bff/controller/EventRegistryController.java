package com.eric.bff.controller;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@Slf4j
@RequestMapping("/events")
public class EventRegistryController {
    

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public EventRegistryController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping
    public ResponseEntity<String> getAllEvents(HttpServletRequest request) {
        return restClient.get()
                .uri(govApiUrl + "/events" + queryString(request))
                .retrieve()
                .toEntity(String.class );
    } 

    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }



    

}
