package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
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
@RequestMapping("/provisioning")
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


    // Antigo endpoint para fluxo de provisioning (token via wifi_ap)
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, String>>> registerDevice(
            @RequestBody RegisterDeviceRequest deviceRequest, 
            HttpServletRequest httpRequest) {
        
        ProvisioningToken token = provisioningService.registerDevice(deviceRequest);
        Map<String, String> data = Map.of("token", token.getToken());
        
        return ResponseEntity.ok(ApiResponse.success(data, httpRequest.getRequestURI()));
    }

    // Endpoint destinado ao esp na fase de provisioning
    @PostMapping("/activate")
    public ResponseEntity<ApiResponse<String>> activateDevice(@RequestBody DeviceRegistrationRequest request, HttpServletRequest httpRequest) {
       
        String certificatePem = provisioningService.processDeviceRegistration(request);
        
        return ResponseEntity.ok(ApiResponse.success(certificatePem, httpRequest.getRequestURI()));
    }

    @PostMapping("/{deviceId}/revoke")
    public ResponseEntity<ApiResponse<String>> revokeDevice(@PathVariable String deviceId, HttpServletRequest httpRequest) throws Exception {
        String msg = deviceRevokeService.revokeDevice(deviceId);
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


}