package com.eric.governanceApi.governanceApi.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

// Leitura do estado ao vivo dos devices (Redis Hash).
@Service
@Slf4j
public class HotStateService {

    public static final String KEY_PREFIX = "device:";
    public static final String KEY_SUFFIX = ":last";

    public static final String FIELD_LAST_SEEN = "last_seen";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_LAT = "lat";
    public static final String FIELD_LON = "lon";

    private final StringRedisTemplate redisTemplate;

    public HotStateService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Estado ao vivo de um device. Map vazio se hash ainda não existe.
    public LiveState getLive(String deviceId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(hashKey(deviceId));
        return LiveState.fromRawMap(raw);
    }

    // Estado ao vivo de múltiplos devices via pipeline
    // Retorna map deviceId -> LiveState. Devices sem entrada no Hash
    // retornam LiveState vazio .
    public Map<String, LiveState> getLiveBulk(Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> ids = List.copyOf(deviceIds);

        // Os comandos precisam ir pela connection do callback, usar o template
        // aqui dentro abre outra conexão e o pipeline retorna vazio.
        List<Object> results = redisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (String id : ids) {
                        connection.hashCommands()
                                .hGetAll(hashKey(id).getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });

        Map<String, LiveState> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Object result = i < results.size() ? results.get(i) : null;
            if (result instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> typed = (Map<Object, Object>) map;
                out.put(ids.get(i), LiveState.fromRawMap(typed));
            } else {
                out.put(ids.get(i), LiveState.empty());
            }
        }
        return out;
    }

    private String hashKey(String deviceId) {
        return KEY_PREFIX + deviceId + KEY_SUFFIX;
    }

    // Snapshot imutável do estado ao vivo de um device. Fields opcionais
    //(Instant/Double são nulos quando ausentes no Hash).
    public record LiveState(
            Instant lastSeen,
            String status,
            Double latitude,
            Double longitude
    ) {

        public static LiveState empty() {
            return new LiveState(null, null, null, null);
        }

        public boolean isPresent() {
            return lastSeen != null || status != null || latitude != null || longitude != null;
        }

        static LiveState fromRawMap(Map<?, ?> raw) {
            if (raw == null || raw.isEmpty()) return empty();
            Instant lastSeen = parseInstant(raw.get(FIELD_LAST_SEEN));
            String status = strOrNull(raw.get(FIELD_STATUS));
            Double lat = parseDouble(raw.get(FIELD_LAT));
            Double lon = parseDouble(raw.get(FIELD_LON));
            return new LiveState(lastSeen, status, lat, lon);
        }

        private static Instant parseInstant(Object o) {
            if (o == null) return null;
            try {
                return Instant.ofEpochMilli(Long.parseLong(o.toString()));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static Double parseDouble(Object o) {
            if (o == null) return null;
            try {
                return Double.parseDouble(o.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String strOrNull(Object o) {
            return o == null ? null : o.toString();
        }
    }
}
