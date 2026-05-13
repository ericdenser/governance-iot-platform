package com.eric.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.eric.agent.model.StatusDTO;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class StatusForwardingService {
    

    private final RestClient restClient;

    public StatusForwardingService(RestClient restClient) {
        this.restClient = restClient;
    }

    // Envia status pro EventHandler
    public void postStatus(StatusDTO dto) {
        // AJUSTAR COM LOG FUTURAMENTE!!!!!!!!!!!!!!!
        log.info("Joined postStatus controller");
        try {
            restClient.post()
                .uri("http://localhost:8081/datalogger/savedata")
                .body(dto)
                .retrieve()
                .toBodilessEntity();
            System.out.println("Status sent");
        } catch (Exception e) {
            System.out.println("Failed to send Status: " + e);
        }
    }

}