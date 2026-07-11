package com.eric.bff.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.eric.bff.config.KeycloakAdminClient;

@RestController
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9._-]{3,64}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final KeycloakAdminClient keycloakAdminClient;

    public UserController(KeycloakAdminClient keycloakAdminClient) {
        this.keycloakAdminClient = keycloakAdminClient;
    }

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> raw = keycloakAdminClient.listUsers();
        List<Map<String, Object>> result = raw.stream()
            .map(UserController::toResponse)
            .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String userId) {
        Map<String, Object> raw = keycloakAdminClient.getUser(userId);
        return ResponseEntity.ok(toResponse(raw));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest req) {
        if (req.username() == null || !USERNAME.matcher(req.username()).matches()) {
            return badRequest("username inválido (3-64 chars, letras/números/._-)");
        }
        if (req.email() != null && !req.email().isBlank() && !EMAIL.matcher(req.email()).matches()) {
            return badRequest("email inválido");
        }
        if (req.temporaryPassword() != null && req.temporaryPassword().length() < 8) {
            return badRequest("senha temporária precisa de ao menos 8 caracteres");
        }

        Map<String, Object> rep = new HashMap<>();
        rep.put("username", req.username());
        rep.put("enabled", req.enabled() == null ? Boolean.TRUE : req.enabled());
        if (req.email() != null && !req.email().isBlank()) rep.put("email", req.email());
        if (req.firstName() != null && !req.firstName().isBlank()) rep.put("firstName", req.firstName());
        if (req.lastName() != null && !req.lastName().isBlank()) rep.put("lastName", req.lastName());
        rep.put("emailVerified", req.emailVerified() == null ? Boolean.FALSE : req.emailVerified());

        if (req.temporaryPassword() != null && !req.temporaryPassword().isBlank()) {
            rep.put("credentials", List.of(Map.of(
                "type", "password",
                "value", req.temporaryPassword(),
                "temporary", true
            )));
        }

        String newId = keycloakAdminClient.createUser(rep);
        logger.info("Usuário criado no Keycloak: id={} username={}", newId, req.username());
        Map<String, Object> created = keycloakAdminClient.getUser(newId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @PutMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable String userId,
                                                          @RequestBody UpdateUserRequest req) {
        if (req.email() != null && !req.email().isBlank() && !EMAIL.matcher(req.email()).matches()) {
            return badRequest("email inválido");
        }

        Map<String, Object> rep = new HashMap<>();
        if (req.email() != null) rep.put("email", req.email());
        if (req.firstName() != null) rep.put("firstName", req.firstName());
        if (req.lastName() != null) rep.put("lastName", req.lastName());
        if (req.enabled() != null) rep.put("enabled", req.enabled());
        if (req.emailVerified() != null) rep.put("emailVerified", req.emailVerified());

        keycloakAdminClient.updateUser(userId, rep);
        logger.info("Usuário atualizado: id={}", userId);
        Map<String, Object> updated = keycloakAdminClient.getUser(userId);
        return ResponseEntity.ok(toResponse(updated));
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        keycloakAdminClient.deleteUser(userId);
        logger.info("Usuário deletado: id={}", userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable String userId,
                                              @RequestBody ResetPasswordRequest req) {
        if (req.newPassword() == null || req.newPassword().length() < 8) {
            ResponseEntity<Map<String, Object>> br = badRequest("senha precisa de ao menos 8 caracteres");
            return ResponseEntity.status(br.getStatusCode()).build();
        }
        boolean temp = req.temporary() == null ? true : req.temporary();
        keycloakAdminClient.resetPassword(userId, req.newPassword(), temp);
        logger.info("Senha resetada para usuário: id={} temporary={}", userId, temp);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> toResponse(Map<String, Object> raw) {
        return Map.of(
            "keycloakUserId", raw.getOrDefault("id", ""),
            "username",       raw.getOrDefault("username", ""),
            "email",          raw.getOrDefault("email", ""),
            "firstName",      raw.getOrDefault("firstName", ""),
            "lastName",       raw.getOrDefault("lastName", ""),
            "enabled",        raw.getOrDefault("enabled", Boolean.FALSE),
            "emailVerified",  raw.getOrDefault("emailVerified", Boolean.FALSE)
        );
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", Map.of("code", "VALIDATION_FAILED", "message", message)
        ));
    }

    public record CreateUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        Boolean emailVerified,
        String temporaryPassword
    ) {}

    public record UpdateUserRequest(
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        Boolean emailVerified
    ) {}

    public record ResetPasswordRequest(
        String newPassword,
        Boolean temporary
    ) {}
}
