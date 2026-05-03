package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.ApiResponse;
import com.eric.governanceApi.governanceApi.model.DeviceErrorRequest;
import com.eric.governanceApi.governanceApi.model.DeviceRegistrationRequest;
import com.eric.governanceApi.governanceApi.model.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.service.DeviceProvisioningService;
import com.eric.governanceApi.governanceApi.service.DeviceRevokeService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/provisioning")
public class DeviceProvisioningController {

    private final DeviceProvisioningService provisioningService;
    private final DeviceRevokeService deviceRevokeService;

    public DeviceProvisioningController(DeviceProvisioningService provisioningService, DeviceRevokeService deviceRevokeService) {
        this.provisioningService = provisioningService;
        this.deviceRevokeService = deviceRevokeService;
    }

   @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, String>>> registerDevice(
            @RequestBody RegisterDeviceRequest deviceRequest, 
            HttpServletRequest httpRequest) {
        
        ProvisioningToken token = provisioningService.registerDevice(deviceRequest);
        Map<String, String> data = Map.of("token", token.getToken());
        
        return ResponseEntity.ok(ApiResponse.success(data, httpRequest.getRequestURI()));
    }

    @PostMapping("/activate")
    public ResponseEntity<ApiResponse<String>> activateDevice(@RequestBody DeviceRegistrationRequest request, HttpServletRequest httpRequest) {
       
        String certificatePem = provisioningService.processDeviceRegistration(request);
        
        return ResponseEntity.ok(ApiResponse.success(certificatePem, httpRequest.getRequestURI()));
    }

    @PostMapping("/{device_id}/revoke")
    public ResponseEntity<ApiResponse<String>> revokeDevice(@PathVariable("device_id") Long device_id, HttpServletRequest httpRequest) throws Exception {
        
        String msg = deviceRevokeService.revokeDevice(device_id);

        return ResponseEntity.ok(ApiResponse.success(msg, httpRequest.getRequestURI()));
    }

    @PostMapping("/error")
    public ResponseEntity<ApiResponse<String>> reportError(@RequestBody DeviceErrorRequest request, HttpServletRequest httpRequest) {
        // Imprime um alerta visual forte no console do servidor
        log.warn("🚨 ALERTA DO DISPOSITIVO [{}]", request.getMac());
        log.warn("IP: {} | SSID: {} | Firmware: v{}", request.getIp(), request.getSsid(), request.getFirmwareVersion());
        log.warn("Falha crítica no processo: {}", request.getCurrentProcess());
        log.warn("error_code: {}", request.getErrorCode());
        log.warn("error_msg: {}", request.getErrorMsg());
        log.warn("error_source: {}", request.getErrorSource());

        // TODO: Chamar o Service para persistir no banco de dados
        // deviceService.logErrorToDatabase(request);

        // Retorna pro esp (trabalhar melhor)
        return ResponseEntity.ok(
            ApiResponse.success("Erro recebido e registrado com sucesso no MDM.", httpRequest.getRequestURI())
        );
    }

}