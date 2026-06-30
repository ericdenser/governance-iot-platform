package com.eric.bff.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.bff.config.KeycloakAdminClient;

@RestController
public class UserController {
    
    private final KeycloakAdminClient keycloakAdminClient;

    public UserController(KeycloakAdminClient keycloakAdminClient) {
        this.keycloakAdminClient = keycloakAdminClient;
    }

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> raw = keycloakAdminClient.listUsers();
        List<Map<String, Object>> result = raw.stream()
            .map(u -> Map.of(
                "keycloakUserId", u.getOrDefault("id", ""),
                "username",       u.getOrDefault("username", ""),
                "email",          u.getOrDefault("email", "") 
            )).toList();

        return ResponseEntity.ok(result);
    }
}
