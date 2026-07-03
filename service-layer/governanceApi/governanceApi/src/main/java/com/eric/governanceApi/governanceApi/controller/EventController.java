package com.eric.governanceApi.governanceApi.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.EventRegistryResponseDTO;
import com.eric.governanceApi.governanceApi.service.DeviceService;
import com.eric.governanceApi.governanceApi.service.EventDispatcherService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/events")
public class EventController {
    
    private final DeviceService deviceService;
    private final EventDispatcherService eventDispatcherService;

    public EventController(EventDispatcherService eventDispatcherService, DeviceService deviceService) {
        this.deviceService = deviceService;
        this.eventDispatcherService = eventDispatcherService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestEvent(@RequestHeader("X-Event-Type") String eventTypeHeader,
    @RequestBody DeviceEventWebhookDTO event) {
    

        eventDispatcherService.dispatch(event);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<EventRegistryResponseDTO>>> listAll(
            @PageableDefault(size = 50) Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.listAllEvents(pageable), httpRequest.getRequestURI()));
    }


}
