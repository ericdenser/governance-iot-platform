package com.eric.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/groups")
public class GroupController {

    private final RestClient restClient;

    @Value("${app.gov-api-url}")
    private String govApiUrl;

    public GroupController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping
    public ResponseEntity<String> list(HttpServletRequest request) {
        return restClient.get()
                .uri(govApiUrl + "/groups" + queryString(request))
                .retrieve().toEntity(String.class);
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return restClient.post()
                .uri(govApiUrl + "/groups")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().toEntity(String.class);
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<String> get(@PathVariable String groupId) {
        return restClient.get()
                .uri(govApiUrl + "/groups/" + groupId)
                .retrieve().toEntity(String.class);
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<String> delete(@PathVariable String groupId) {
        return restClient.delete()
                .uri(govApiUrl + "/groups/" + groupId)
                .retrieve().toEntity(String.class);
    }

    // ── Device membership ─────────────────────────────────────────────────────

    @GetMapping("/{groupId}/devices")
    public ResponseEntity<String> listDevices(@PathVariable String groupId) {
        return restClient.get()
                .uri(govApiUrl + "/groups/" + groupId + "/devices")
                .retrieve().toEntity(String.class);
    }

    @PutMapping("/{groupId}/devices/{deviceId}")
    public ResponseEntity<String> addDevice(@PathVariable String groupId, @PathVariable String deviceId) {
        return restClient.put()
                .uri(govApiUrl + "/groups/" + groupId + "/devices/" + deviceId)
                .retrieve().toEntity(String.class);
    }

    @DeleteMapping("/{groupId}/devices/{deviceId}")
    public ResponseEntity<String> removeDevice(@PathVariable String groupId, @PathVariable String deviceId) {
        return restClient.delete()
                .uri(govApiUrl + "/groups/" + groupId + "/devices/" + deviceId)
                .retrieve().toEntity(String.class);
    }

    @GetMapping("/{groupId}/devices/{deviceId}/membership")
    public ResponseEntity<String> checkMembership(@PathVariable String groupId, @PathVariable String deviceId) {
        return restClient.get()
                .uri(govApiUrl + "/groups/" + groupId + "/devices/" + deviceId + "/membership")
                .retrieve().toEntity(String.class);
    }

    // ── User assignment ───────────────────────────────────────────────────────

    @GetMapping("/{groupId}/users")
    public ResponseEntity<String> listUsers(@PathVariable String groupId) {
        return restClient.get()
                .uri(govApiUrl + "/groups/" + groupId + "/users")
                .retrieve().toEntity(String.class);
    }

    @PutMapping("/{groupId}/users")
    public ResponseEntity<String> assignUser(@PathVariable String groupId, @RequestBody String body) {
        return restClient.put()
                .uri(govApiUrl + "/groups/" + groupId + "/users")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().toEntity(String.class);
    }

    @PatchMapping("/{groupId}/users/{keycloakUserId}")
    public ResponseEntity<String> updateUserRole(@PathVariable String groupId, @PathVariable String keycloakUserId,
                                                 @RequestBody String body) {
        return restClient.patch()
                .uri(govApiUrl + "/groups/" + groupId + "/users/" + keycloakUserId)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().toEntity(String.class);
    }

    @DeleteMapping("/{groupId}/users/{keycloakUserId}")
    public ResponseEntity<String> removeUser(@PathVariable String groupId, @PathVariable String keycloakUserId) {
        return restClient.delete()
                .uri(govApiUrl + "/groups/" + groupId + "/users/" + keycloakUserId)
                .retrieve().toEntity(String.class);
    }

    private String queryString(HttpServletRequest request) {
        String qs = request.getQueryString();
        return qs != null ? "?" + qs : "";
    }
}
