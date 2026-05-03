package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.ApiResponse;
import com.eric.governanceApi.governanceApi.model.Firmware;
import com.eric.governanceApi.governanceApi.service.FirmwareService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/firmware")
public class FirmwareController {

    private final FirmwareService firmwareService;

    public FirmwareController(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    // documentar melhor

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Firmware>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("version") int version,
            @RequestParam(value = "releaseNotes", required = false) String releaseNotes,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Upload firmware v{} — {} ({} bytes)",
                 version, file.getOriginalFilename(), file.getSize());

        Firmware fw = firmwareService.upload(file, version, releaseNotes);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Firmware>>> listAll(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listAll(), httpRequest.getRequestURI()));
    }

    @GetMapping("/deployable")
    public ResponseEntity<ApiResponse<List<Firmware>>> listDeployable(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            ApiResponse.success(firmwareService.listDeployable(), httpRequest.getRequestURI()));
    }

    @PatchMapping("/{id}/deprecate")
    public ResponseEntity<ApiResponse<Firmware>> deprecate(
            @PathVariable Long id, HttpServletRequest httpRequest) {

        Firmware fw = firmwareService.deprecate(id);
        return ResponseEntity.ok(ApiResponse.success(fw, httpRequest.getRequestURI()));
    }
}