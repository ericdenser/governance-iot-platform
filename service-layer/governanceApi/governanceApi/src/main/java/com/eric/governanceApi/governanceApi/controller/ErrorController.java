package com.eric.governanceApi.governanceApi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.model.request.DeviceErrorDTO;
import com.eric.governanceApi.governanceApi.service.ErrorDispatcherService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/error")
public class ErrorController {

    private final ErrorDispatcherService errorDispatcherService;

    public ErrorController(ErrorDispatcherService errorDispatcherService) {
        this.errorDispatcherService = errorDispatcherService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestError(@RequestBody DeviceErrorDTO errorDTO) {

        log.info("Recebeu no ErrorController {}", errorDTO);
        errorDispatcherService.dispatch(errorDTO);
        return ResponseEntity.ok().build();
    }

}
