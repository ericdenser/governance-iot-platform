package com.eric.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/devices")
public class DeviceController {

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public DeviceController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping
    public ResponseEntity<String> listAll() {
        return restClient.get()
                .uri(govApiUrl + "/devices")
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<String> getDevice(@PathVariable String deviceId) {
        return restClient.get()
                .uri(govApiUrl + "/devices/" + deviceId)
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{deviceId}/commands")
    public ResponseEntity<String> getCommands(@PathVariable String deviceId, HttpServletRequest request) {
        return restClient.get()
                .uri(govApiUrl + "/devices/" + deviceId + "/commands" + queryString(request))
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{deviceId}/errors")
    public ResponseEntity<String> getErrors(@PathVariable String deviceId, HttpServletRequest request) {
        return restClient.get()
                .uri(govApiUrl + "/devices/" + deviceId + "/errors" + queryString(request))
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{deviceId}/events")
    public ResponseEntity<String> getEvents(@PathVariable String deviceId, HttpServletRequest request) {
        return restClient.get()
                .uri(govApiUrl + "/devices/" + deviceId + "/events" + queryString(request))
                .retrieve()
                .toEntity(String.class);
    }

    @GetMapping("/{deviceId}/certificate")
    public ResponseEntity<String> getCertificate(@PathVariable String deviceId) {
        return restClient.get()
                .uri(govApiUrl + "/devices/" + deviceId + "/certificate")
                .retrieve()
                .toEntity(String.class);
    }


    // PROVISIONING ==================================

    @PostMapping("/register")
    public ResponseEntity<String> registerDevice(@RequestBody String body) {
        return restClient.post()
                .uri(govApiUrl + "/provisioning/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    @PostMapping("/{deviceId}/revoke")
    public ResponseEntity<String> revokeDevice(@PathVariable String deviceId) {
        return restClient.post()
                .uri(govApiUrl + "/provisioning/" + deviceId + "/revoke")
                .retrieve()
                .toEntity(String.class);
    }

    @PostMapping("/generate-package")
    public ResponseEntity<byte[]> generateFlashPackage(@RequestBody String body) {
        ResponseEntity<byte[]> upstream = restClient.post()
                .uri(govApiUrl + "/provisioning/generate-package")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(byte[].class);

        String disposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition != null ? disposition : "attachment; filename=\"flash_package.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(upstream.getBody());
    }

    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }
}
