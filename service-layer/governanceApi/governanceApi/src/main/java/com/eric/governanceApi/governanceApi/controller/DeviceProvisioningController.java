package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.request.DeviceErrorRequest;
import com.eric.governanceApi.governanceApi.model.request.DeviceRegistrationRequest;
import com.eric.governanceApi.governanceApi.model.request.GenerateFlashPackageRequest;
import com.eric.governanceApi.governanceApi.model.request.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.service.DeviceProvisioningService;
import com.eric.governanceApi.governanceApi.service.DeviceRevokeService;
import com.eric.governanceApi.governanceApi.service.FlashPackageService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/provisioning")
public class DeviceProvisioningController {

    private final DeviceProvisioningService provisioningService;
    private final DeviceRevokeService deviceRevokeService;
    private final FlashPackageService flashPackageService;

    public DeviceProvisioningController(DeviceProvisioningService provisioningService,
                                        DeviceRevokeService deviceRevokeService,
                                        FlashPackageService flashPackageService) {
        this.provisioningService = provisioningService;
        this.deviceRevokeService = deviceRevokeService;
        this.flashPackageService = flashPackageService;
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

    @PostMapping("/generate-package")
    public ResponseEntity<byte[]> generateFlashPackage(@RequestBody GenerateFlashPackageRequest request) throws IOException {
        byte[] zip = flashPackageService.generatePackage(request);
        String filename = "flash_package_" + request.deviceName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    @PostMapping("/error")
    public ResponseEntity<ApiResponse<String>> reportError(@RequestBody DeviceErrorRequest request, HttpServletRequest httpRequest) {
        // Imprime um alerta visual forte no console do servidor
        log.warn("🚨 ALERTA DO DISPOSITIVO [{}]", request.getDeviceId());
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