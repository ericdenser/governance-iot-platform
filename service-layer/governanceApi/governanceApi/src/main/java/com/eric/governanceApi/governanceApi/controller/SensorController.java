package com.eric.governanceApi.governanceApi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.model.request.SensorDTO;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.SensorResponseDTO;
import com.eric.governanceApi.governanceApi.service.SensorService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/sensor")
public class SensorController {
    
    private final SensorService sensorService;

    public SensorController(SensorService sensorService) {
        this.sensorService = sensorService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<SensorResponseDTO>> uploadSensor(@Valid @RequestBody SensorDTO sensor, HttpServletRequest httpRequest) {
        SensorResponseDTO response = sensorService.registerSensor(sensor);
        return ResponseEntity.ok(ApiResponse.success(response, httpRequest.getRequestURI()));
    }
}
