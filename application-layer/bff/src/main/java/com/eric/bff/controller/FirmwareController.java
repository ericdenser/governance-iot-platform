package com.eric.bff.controller;

import org.springframework.beans.factory.annotation.Value;
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

    @GetMapping
    public ResponseEntity<String> listAll() {
        return restClient.get()
                .uri(govApiUrl + "/firmware")
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/deployable")
    public ResponseEntity<String> listDeployable() {
        return restClient.get()
                .uri(govApiUrl + "/firmware/deployable")
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

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("metadata") String metadata) throws Exception {

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", file.getResource());
        parts.add("metadata", metadata);

        return restClient.post()
                .uri(govApiUrl + "/firmware/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(String.class);
    }

    @PatchMapping("/{firmwareId}/deprecate")
    public ResponseEntity<String> deprecate(@PathVariable String firmwareId) {
        return restClient.patch()
                .uri(govApiUrl + "/firmware/" + firmwareId + "/deprecate")
                .retrieve()
                .toEntity(String.class);
    }

    @PutMapping("/{firmwareId}/provisioning")
    public ResponseEntity<String> setProvisioning(@PathVariable String firmwareId) {
        return restClient.put()
                .uri(govApiUrl + "/firmware/" + firmwareId + "/provisioning")
                .retrieve()
                .toEntity(String.class);
    }
}
