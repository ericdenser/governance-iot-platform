package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.request.FirmwareUploadMetadataDTO;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.FirmwareResponseDTO;
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

    @GetMapping("/{firmwareId}")
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> getOne(
            @PathVariable String firmwareId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(firmwareService.getByFirmwareId(firmwareId), httpRequest.getRequestURI()));
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") FirmwareUploadMetadataDTO metadataDTO,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Request for upload firmware v{} — {} ({} bytes)",
                 metadataDTO.version(), file.getOriginalFilename(), file.getSize());

        FirmwareResponseDTO fw = firmwareService.upload(file, metadataDTO);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FirmwareResponseDTO>>> listAll(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listAll(), httpRequest.getRequestURI()));
    }

    @GetMapping("/deployable")
    public ResponseEntity<ApiResponse<List<FirmwareResponseDTO>>> listDeployable(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listDeployable(), httpRequest.getRequestURI()));
    }

    @PatchMapping("/{firmwareId}/deprecate")
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> deprecate(
            @PathVariable String firmwareId, HttpServletRequest httpRequest) {

        FirmwareResponseDTO fw = firmwareService.deprecate(firmwareId);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    @PutMapping("/{firmwareId}/provisioning")
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> setProvisioning(
            @PathVariable String firmwareId, HttpServletRequest httpRequest) {

        FirmwareResponseDTO fw = firmwareService.setProvisioningFirmware(firmwareId);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

}