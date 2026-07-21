package com.eric.governanceApi.governanceApi.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints chamados pelo Mosquitto (plugin mosquitto-go-auth em modo remote)
 * para validar autenticação e ACL de conexões MQTT.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class MqttAuthController {

    private final JwtDecoder jwtDecoder;

    public MqttAuthController(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @PostMapping("/mqtt-verify")
    public ResponseEntity<PluginResponse> verify(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) java.util.Map<String,Object> body) {
        String jwtToken = extractJwt(authHeader);
        if (jwtToken == null) {
            return ResponseEntity.ok(PluginResponse.failure("Authorization Bearer ausente"));
        }
        try {
            Jwt jwt = jwtDecoder.decode(jwtToken);
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || azp.isBlank()) {
                return ResponseEntity.ok(PluginResponse.failure("azp claim ausente"));
            }
            log.debug("mqtt-verify OK azp={}", azp);
            return ResponseEntity.ok(PluginResponse.success());
        } catch (JwtException e) {
            log.debug("mqtt-verify denied: {}", e.getMessage());
            return ResponseEntity.ok(PluginResponse.failure(e.getMessage()));
        }
    }

    @PostMapping("/mqtt-acl")
    public ResponseEntity<PluginResponse> acl(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String,Object> body) {
        String jwtToken = extractJwt(authHeader);
        if (jwtToken == null) {
            return ResponseEntity.ok(PluginResponse.failure("Authorization Bearer ausente"));
        }
        String topic = body == null ? null : asString(body.get("topic"));
        Object accRaw = body == null ? null : body.get("acc");
        int acc = accRaw instanceof Number n ? n.intValue()
                : (accRaw instanceof String s ? Integer.parseInt(s) : 0);
        try {
            Jwt jwt = jwtDecoder.decode(jwtToken);
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || azp.isBlank()) {
                return ResponseEntity.ok(PluginResponse.failure("azp claim ausente"));
            }
            if (isAllowed(azp, topic, acc)) {
                return ResponseEntity.ok(PluginResponse.success());
            }
            log.debug("mqtt-acl DENIED azp={} topic={} acc={}", azp, topic, acc);
            return ResponseEntity.ok(PluginResponse.failure(
                "topic " + topic + " nao autorizado para " + azp));
        } catch (JwtException e) {
            return ResponseEntity.ok(PluginResponse.failure(e.getMessage()));
        }
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static String extractJwt(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) return null;
        String h = authHeader.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return h.substring(7).trim();
        }
        return null;
    }

    // Clients privilegiados (service accounts internos) -> acesso total ao broker.
    // agent-mqtt: recebe status/telemetry/error de todos os devices, publica commands.
    private static final Set<String> PRIVILEGED_CLIENTS = Set.of(
        "agent-mqtt"
    );

    /**
     * Regras ACL:
     *   agent-mqtt (privileged) → tudo
     *   device (azp = UUID)     → status/{azp}, telemetry/{azp}, error/{azp} readwrite;
     *                             commands/{azp}/* read (subscribe)
     */
    private boolean isAllowed(String azp, String topic, int acc) {
        if (topic == null) return false;

        // Service accounts internos têm acesso total (backends confiáveis)
        if (PRIVILEGED_CLIENTS.contains(azp)) {
            return true;
        }

        // Devices — só o próprio "canal"
        if (topic.equals("status/" + azp)
            || topic.equals("telemetry/" + azp)
            || topic.equals("error/" + azp)) {
            return true;  // readwrite (acc 1|2|3)
        }
        if (topic.startsWith("commands/" + azp + "/") || topic.equals("commands/" + azp)) {
            return acc == 1 || acc == 4;  // read/subscribe apenas
        }
        return false;
    }

    // ─── DTOs ───────────────────────────────────────────────────────────────

    public record VerifyRequest(String username, String password, String clientid) {}
    public record AclRequest(String username, String clientid, String topic, int acc) {}

    public record PluginResponse(@JsonProperty("Ok") boolean ok,
                                 @JsonProperty("Error") String error) {
        // Factories NÃO podem chamar-se ok()/error() — colidem com os accessors do record.
        static PluginResponse success()             { return new PluginResponse(true, ""); }
        static PluginResponse failure(String err)   { return new PluginResponse(false, err); }
    }
}
