package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.request.FirmwareUploadMetadataDTO;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.service.FirmwareService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/firmware")
public class FirmwareController {

    private final FirmwareService firmwareService;

    public FirmwareController(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    // documentar melhor

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Firmware>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") FirmwareUploadMetadataDTO metadataDTO,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Upload firmware v{} — {} ({} bytes)",
                 metadataDTO.version(), file.getOriginalFilename(), file.getSize());

        // TODO trocar por firmwareResponseDTO
        Firmware fw = firmwareService.upload(file, metadataDTO);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    // TODO trocar por firmwareResponseDTO
    @GetMapping
    public ResponseEntity<ApiResponse<List<Firmware>>> listAll(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listAll(), httpRequest.getRequestURI()));
    }

    // TODO trocar por firmwareResponseDTO
    @GetMapping("/deployable")
    public ResponseEntity<ApiResponse<List<Firmware>>> listDeployable(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listDeployable(), httpRequest.getRequestURI()));
    }

    // TODO trocar por firmwareResponseDTO
    @PatchMapping("/{id}/deprecate")
    public ResponseEntity<ApiResponse<Firmware>> deprecate(
            @PathVariable Long id, HttpServletRequest httpRequest) {

        // TODO trocar por firmwareResponseDTO
        Firmware fw = firmwareService.deprecate(id);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    // TODO trocar por firmwareResponseDTO
    @PutMapping("/{id}/provisioning")
    public ResponseEntity<ApiResponse<Firmware>> setProvisioning(
            @PathVariable Long id, HttpServletRequest httpRequest) {
        
                // TODO trocar por firmwareResponseDTO
        Firmware fw = firmwareService.setProvisioningFirmware(id);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

}