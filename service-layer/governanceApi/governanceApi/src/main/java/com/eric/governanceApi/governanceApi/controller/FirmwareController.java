package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.projection.DeployableVersionProjection;
import com.eric.governanceApi.governanceApi.model.request.CreateFirmwareRequestDTO;
import com.eric.governanceApi.governanceApi.model.request.UploadVersionRequestDTO;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.FirmwareResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.FirmwareVersionResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.FirmwareVersionSummaryDTO;
import com.eric.governanceApi.governanceApi.service.FirmwareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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

    // ─── Firmware (product) ────────────────────────────────────────────────────

    // Lista de firmwares, cada linha traz `latestVersion` embarcada. 
    @GetMapping
    public ResponseEntity<ApiResponse<List<FirmwareResponseDTO>>> listAll(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listFirmware(), httpRequest.getRequestURI()));
    }

    // Detalhe de um firmware (product). 
    @GetMapping("/{firmwareId}")
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> getOne(
            @PathVariable String firmwareId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.getByFirmwareId(firmwareId), httpRequest.getRequestURI()));
    }

    // Cria um novo firmware + primeira versão. 
    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> createFirmware(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("metadata") CreateFirmwareRequestDTO metadataDTO,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Request for create firmware '{}' — {} ({} bytes)",
                 metadataDTO.firmwareName(), file.getOriginalFilename(), file.getSize());

        FirmwareResponseDTO fw = firmwareService.createFirmware(file, metadataDTO);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    // Define um firmware como provisionamento — ADMIN only
    @PutMapping("/{firmwareId}/provisioning")
    public ResponseEntity<ApiResponse<FirmwareResponseDTO>> setProvisioning(
            @PathVariable String firmwareId,
            HttpServletRequest httpRequest) {

        FirmwareResponseDTO fw = firmwareService.setProvisioningFirmware(firmwareId);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    // ─── Firmware Version ──────────────────────────────────────────────────────

    // Lista compacta de versões de um firmware
    @GetMapping("/{firmwareId}/versions")
    public ResponseEntity<ApiResponse<List<FirmwareVersionSummaryDTO>>> listVersions(
            @PathVariable String firmwareId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listVersions(firmwareId), httpRequest.getRequestURI()));
    }

    // Detalhe completo de uma versão
    @GetMapping("/versions/{versionId}")
    public ResponseEntity<ApiResponse<FirmwareVersionResponseDTO>> getVersion(
            @PathVariable String versionId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.getByVersionId(versionId), httpRequest.getRequestURI()));
    }

    // Sobe uma nova versão a um firmware existente. 
    @PostMapping(value = "/{firmwareId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FirmwareVersionResponseDTO>> uploadVersion(
            @PathVariable String firmwareId,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("metadata") UploadVersionRequestDTO metadataDTO,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Request for upload version v{} for firmware {}", metadataDTO.version(), firmwareId);
        FirmwareVersionResponseDTO v = firmwareService.uploadNewVersion(firmwareId, file, metadataDTO);
        return ResponseEntity.ok(ApiResponse.success(v, httpRequest.getRequestURI()));
    }

    // Deprecia UMA versão específica. 
    @PatchMapping("/versions/{versionId}/deprecate")
    public ResponseEntity<ApiResponse<FirmwareVersionResponseDTO>> deprecate(
            @PathVariable String versionId,
            HttpServletRequest httpRequest) {

        FirmwareVersionResponseDTO v = firmwareService.deprecateVersion(versionId);
        return ResponseEntity.ok(ApiResponse.success(v, httpRequest.getRequestURI()));
    }

    // ─── Deploy helper ─────────────────────────────────────────────────────────

    // Versões deployáveis (não-DEPRECATED) filtradas pelo escopo do usuário
    @GetMapping("/deployable")
    public ResponseEntity<ApiResponse<List<DeployableVersionProjection>>> listDeployable(
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listDeployable(), httpRequest.getRequestURI()));
    }
}
