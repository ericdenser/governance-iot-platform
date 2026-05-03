package com.eric.agent.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.eric.agent.model.DataDTO;

@Service
public class DataForwardingService {
    

    private final RestClient restClient;

    public DataForwardingService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void postDataLogger(DataDTO dto) {
        // AJUSTAR COM LOG FUTURAMENTE!!!!!!!!!!!!!!!
        System.out.println("Joined postDataLogger controller");
        try {
            restClient.post()
                .uri("http://localhost:8081/datalogger/savedata")
                .body(dto)
                .retrieve()
                .toBodilessEntity();
            System.out.println("Data sent");
        } catch (Exception e) {
            System.out.println("Failed to send Data: " + e);
        }
    }

}