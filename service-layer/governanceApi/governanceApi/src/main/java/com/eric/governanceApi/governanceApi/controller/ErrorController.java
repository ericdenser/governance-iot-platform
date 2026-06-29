package com.eric.governanceApi.governanceApi.controller;

import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.eric.governanceApi.governanceApi.model.request.DeviceErrorDTO;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.ErrorRecordResponseDTO;
import com.eric.governanceApi.governanceApi.service.DeviceService;
import com.eric.governanceApi.governanceApi.service.ErrorDispatcherService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/error")
public class ErrorController {

    private final ErrorDispatcherService errorDispatcherService;
    private final DeviceService deviceService;

    public ErrorController(ErrorDispatcherService errorDispatcherService, DeviceService deviceService) {
        this.errorDispatcherService = errorDispatcherService;
        this.deviceService = deviceService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingestError(@RequestBody DeviceErrorDTO errorDTO) {

        log.info("Recebeu no ErrorController {}", errorDTO);
        errorDispatcherService.dispatch(errorDTO);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ErrorRecordResponseDTO>>> listAll(
            @PageableDefault(size = 50) Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.listAllErrors(pageable), httpRequest.getRequestURI()));
    }

}
