package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceCertificateResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceDetailDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceSummaryDTO;
import com.eric.governanceApi.governanceApi.model.response.ErrorRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.EventRegistryResponseDTO;
import com.eric.governanceApi.governanceApi.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<DeviceSummaryDTO>>> listAll(
        @PageableDefault(size = 50, sort = "name") Pageable pageable,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) DeviceStatus status,
        HttpServletRequest httpRequest) {

        return ResponseEntity.ok(ApiResponse.success(deviceService.listAll(pageable, search, status), httpRequest.getRequestURI()));
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceDetailDTO>> getDevice(
            @PathVariable String deviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getDevice(deviceId), httpRequest.getRequestURI()));
    }

    @GetMapping("/{deviceId}/commands")
    public ResponseEntity<ApiResponse<Page<CommandRecordResponseDTO>>> getCommands(
            @PathVariable String deviceId,
            @PageableDefault(size = 20, sort = "sentAt") Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getCommands(deviceId, pageable), httpRequest.getRequestURI()));
    }

    @GetMapping("/{deviceId}/errors")
    public ResponseEntity<ApiResponse<Page<ErrorRecordResponseDTO>>> getErrors(
            @PathVariable String deviceId,
            @PageableDefault(size = 20, sort = "reportedAt") Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getErrors(deviceId, pageable), httpRequest.getRequestURI()));
    }

    @GetMapping("/{deviceId}/events")
    public ResponseEntity<ApiResponse<Page<EventRegistryResponseDTO>>> getEvents(
            @PathVariable String deviceId,
            @PageableDefault(size = 20, sort = "uploadedAt", direction = Sort.Direction.DESC) Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getEvents(deviceId, pageable), httpRequest.getRequestURI()));
    }

    @GetMapping("/{deviceId}/certificate")
    public ResponseEntity<ApiResponse<DeviceCertificateResponseDTO>> getCertificate(
            @PathVariable String deviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getCertificate(deviceId), httpRequest.getRequestURI()));
    }
}
