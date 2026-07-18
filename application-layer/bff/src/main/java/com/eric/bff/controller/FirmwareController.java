package com.eric.bff.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/firmware")
public class FirmwareController {

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public FirmwareController(RestClient restClient) {
        this.restClient = restClient;
    }

    // ─── Firmware (product) ────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<String> listAll() {
        return restClient.get()
                .uri(govApiUrl + "/firmware")
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{firmwareId}")
    public ResponseEntity<String> getOne(@PathVariable String firmwareId) {
        return restClient.get()
                .uri(govApiUrl + "/firmware/" + firmwareId)
                .retrieve()
                .toEntity(String.class);
    }

    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> createFirmware(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") String metadata) throws Exception {

        return relayMultipart(govApiUrl + "/firmware/create", file, metadata);
    }

    @PutMapping("/{firmwareId}/provisioning")
    public ResponseEntity<String> setProvisioning(@PathVariable String firmwareId) {
        return restClient.put()
                .uri(govApiUrl + "/firmware/" + firmwareId + "/provisioning")
                .retrieve()
                .toEntity(String.class);
    }

    // ─── Firmware Version ──────────────────────────────────────────────────────

    @GetMapping("/{firmwareId}/versions")
    public ResponseEntity<String> listVersions(@PathVariable String firmwareId) {
        return restClient.get()
                .uri(govApiUrl + "/firmware/" + firmwareId + "/versions")
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<String> getVersion(@PathVariable String versionId) {
        return restClient.get()
                .uri(govApiUrl + "/firmware/versions/" + versionId)
                .retrieve()
                .toEntity(String.class);
    }

    @PostMapping(value = "/{firmwareId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadVersion(
            @PathVariable String firmwareId,
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") String metadata) throws Exception {

        return relayMultipart(govApiUrl + "/firmware/" + firmwareId + "/upload", file, metadata);
    }

    @PatchMapping("/versions/{versionId}/deprecate")
    public ResponseEntity<String> deprecate(@PathVariable String versionId) {
        return restClient.patch()
                .uri(govApiUrl + "/firmware/versions/" + versionId + "/deprecate")
                .retrieve()
                .toEntity(String.class);
    }

    @PutMapping(value = "/versions/{versionId}/binary", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> reuploadBinary(
            @PathVariable String versionId,
            @RequestPart("file") MultipartFile file) {

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", file.getResource());

        return restClient.put()
                .uri(govApiUrl + "/firmware/versions/" + versionId + "/binary")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(String.class);
    }

    @DeleteMapping("/versions/{versionId}")
    public ResponseEntity<String> deleteVersion(@PathVariable String versionId) {
        return restClient.delete()
                .uri(govApiUrl + "/firmware/versions/" + versionId)
                .retrieve()
                .toEntity(String.class);
    }

    @DeleteMapping("/{firmwareId}")
    public ResponseEntity<String> deleteFirmware(@PathVariable String firmwareId) {
        return restClient.delete()
                .uri(govApiUrl + "/firmware/" + firmwareId)
                .retrieve()
                .toEntity(String.class);
    }

    // ─── Deploy helper ─────────────────────────────────────────────────────────

    @GetMapping("/deployable")
    public ResponseEntity<String> listDeployable() {
        return restClient.get()
                .uri(govApiUrl + "/firmware/deployable")
                .retrieve()
                .toEntity(String.class);
    }

    // ─── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> relayMultipart(String url, MultipartFile file, String metadata) {
        HttpHeaders metadataHeaders = new HttpHeaders();
        metadataHeaders.setContentType(MediaType.APPLICATION_JSON);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", file.getResource());
        parts.add("metadata", new HttpEntity<>(metadata, metadataHeaders));

        return restClient.post()
                .uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(String.class);
    }
}
