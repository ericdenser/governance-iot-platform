package com.eric.governanceApi.governanceApi.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.ErrorRecordResponseDTO;
import com.eric.governanceApi.governanceApi.service.DeviceService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoint de leitura de erros de device.
 *
 * O antigo POST /error/ingest foi removido no Obj 11 Fase C — o ingest agora vem
 * de stream:error (Redis) consumido por ErrorStreamConsumer, que chama
 * ErrorDispatcherService.dispatch diretamente.
 */
@Slf4j
@RestController
@RequestMapping("/error")
public class ErrorController {

    private final DeviceService deviceService;

    public ErrorController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<ErrorRecordResponseDTO>>> listAll(
            @PageableDefault(size = 50) Pageable pageable,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(
                deviceService.listAllErrors(pageable),
                httpRequest.getRequestURI()
        ));
    }
}
