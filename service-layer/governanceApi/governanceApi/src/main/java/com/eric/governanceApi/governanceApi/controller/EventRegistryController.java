package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.EventRegistryResponseDTO;
import com.eric.governanceApi.governanceApi.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/events")
public class EventRegistryController {

    private final DeviceService deviceService;

    public EventRegistryController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<EventRegistryResponseDTO>>> listAll(
            @PageableDefault(size = 50) Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.listAllEvents(pageable), httpRequest.getRequestURI()));
    }
}
