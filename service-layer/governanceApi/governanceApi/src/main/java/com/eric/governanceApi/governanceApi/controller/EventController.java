package com.eric.governanceApi.governanceApi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.model.dto.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.service.EventDispatcherService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/event")
public class EventController {
    
    private final EventDispatcherService eventDispatcherService;

    public EventController(EventDispatcherService eventDispatcherService) {
        this.eventDispatcherService = eventDispatcherService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestEvent(@RequestHeader("X-Event-Type") String eventTypeHeader,
    @RequestBody DeviceEventWebhookDTO event) {
    

        eventDispatcherService.dispatch(event);
        return ResponseEntity.ok().build();
    }

}
