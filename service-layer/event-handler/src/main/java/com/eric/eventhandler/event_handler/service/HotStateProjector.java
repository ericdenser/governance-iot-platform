package com.eric.eventhandler.event_handler.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.entity.DeviceEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

// Projeta stream:status e stream:telemetry no Redis Hash "device:{id}:last".
// govApi consulta HGETALL pra retornar lastSeen/lat/lon/status.
// hotstate
@Service
@Slf4j
public class HotStateProjector {

    private static final String GROUP = "hotstate-group";
    private static final String STREAM_STATUS = "stream:status";
    private static final String STREAM_TELEMETRY = "stream:telemetry";
    private static final String HASH_PREFIX = "device:";
    private static final String HASH_SUFFIX = ":last";

    // Canal de broadcast do estado ao vivo pra SSE do govApi
    // Após cada HSET, publicamos o estado completo agregado do device
    private static final String CHANNEL_DEVICE_LIVE = "channel:device-live";

    private static final int SWEEP_BATCH = 100;
    private static final long MAX_DELIVERIES = 5;
    private static final Duration SWEEP_MIN_IDLE = Duration.ofSeconds(60);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HealthEvaluator healthEvaluator;
    private final EventDispatcher eventDispatcher;

    // Estável entre restarts (sem PID) — herda o próprio PEL ao subir de novo.
    private final String consumerName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription statusSubscription;
    private Subscription telemetrySubscription;

    public HotStateProjector(RedisConnectionFactory connectionFactory,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             HealthEvaluator healthEvaluator,
                             EventDispatcher eventDispatcher,
                             @Value("${app.streams.replica-id:0}") String replicaId) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.healthEvaluator = healthEvaluator;
        this.eventDispatcher = eventDispatcher;
        this.consumerName = "eventhandler-hotstate-" + replicaId;
    }

    @PostConstruct
    public void start() {
        ensureGroup(STREAM_STATUS);
        ensureGroup(STREAM_TELEMETRY);

        var options = StreamMessageListenerContainer
                .StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(1000))
                .batchSize(100)
                .build();

        container = StreamMessageListenerContainer.create(connectionFactory, options);

        statusSubscription = container.receive(
                Consumer.from(GROUP, consumerName),
                StreamOffset.create(STREAM_STATUS, ReadOffset.lastConsumed()),
                this::handleStatus
        );

        telemetrySubscription = container.receive(
                Consumer.from(GROUP, consumerName),
                StreamOffset.create(STREAM_TELEMETRY, ReadOffset.lastConsumed()),
                this::handleTelemetry
        );

        container.start();
        log.info("HotStateProjector iniciado: group={} consumer={} streams=[{}, {}]",
                GROUP, consumerName, STREAM_STATUS, STREAM_TELEMETRY);
    }

    private void handleStatus(MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        String envelopeTimestamp = record.getValue().get("timestamp");
        Map<String, Object> dto = parseOrDiscard(payload, record);
        if (dto == null) return;
        try {
            String deviceId = strOrNull(dto.get("deviceId"));
            if (deviceId == null) {
                redisTemplate.opsForStream().acknowledge(GROUP, record);
                return;
            }

            Map<String, String> fields = new HashMap<>();
            fields.put("last_seen", envelopeTimestamp);
            Object status = dto.get("status");
            if (status != null) {
                // Vem como número do protobuf ("5"); converte pro vocabulário do CMDB
                String mapped = toCmdbStatus(DeviceState.fromValue(String.valueOf(status)));
                if (mapped != null) fields.put("status", mapped);
            }

            redisTemplate.opsForHash().putAll(hashKey(deviceId), fields);
            publishLive(deviceId);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Falha projetando status id={}: {}", record.getId(), e.getMessage());
            // sem ACK -> reentrega
        }
    }

    private void handleTelemetry(MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        String envelopeTimestamp = record.getValue().get("timestamp");
        Map<String, Object> dto = parseOrDiscard(payload, record);
        if (dto == null) return;
        try {
            String deviceId = strOrNull(dto.get("deviceId"));
            if (deviceId == null) {
                redisTemplate.opsForStream().acknowledge(GROUP, record);
                return;
            }

            Map<String, String> fields = new HashMap<>();
            fields.put("last_seen", envelopeTimestamp);
            Double batteryReading = null;
            Double tempReading = null;

            // TelemetryDTO.readings = Map<String, Float> — extrai lat/lon/bateria se presentes.
            // battery_ts é gravado SEPARADO do last_seen porque nem toda telemetria traz
            // battery_mv (device sem ADC), e o consumidor precisa saber a idade real da leitura.
            Object readings = dto.get("readings");
            if (readings instanceof Map<?, ?> map) {
                Object lat = map.get("latitude");
                Object lon = map.get("longitude");
                Object batteryMv = map.get("battery_mv");
                Object rssi = map.get("rssi");
                Object tempC = map.get("temp_c");
                if (lat != null) fields.put("latitude", String.valueOf(lat));
                if (lon != null) fields.put("longitude", String.valueOf(lon));
                if (batteryMv != null) {
                    batteryReading = toDouble(batteryMv);
                    fields.put("battery_mv", String.valueOf(batteryMv));
                    fields.put("battery_ts", envelopeTimestamp);
                }
                if (rssi != null) {
                    fields.put("rssi", String.valueOf(rssi));
                    fields.put("rssi_ts", envelopeTimestamp);
                }
                if (tempC != null) {
                    tempReading = toDouble(tempC);
                    fields.put("temp_c", String.valueOf(tempC));
                    fields.put("temp_ts", envelopeTimestamp);
                }
            }

            // health so muda (e gera evento) quando o conjunto de motivos muda
            Set<String> previousReasons = parseReasons(
                    redisTemplate.opsForHash().get(hashKey(deviceId), "health_reason"));
            Set<String> reasons = healthEvaluator.evaluate(previousReasons, batteryReading, tempReading);
            boolean healthChanged = !reasons.equals(previousReasons);
            if (healthChanged) {
                fields.put("health", reasons.isEmpty() ? "OK" : "WARNING");
                fields.put("health_reason", String.join(",", reasons));
            }

            redisTemplate.opsForHash().putAll(hashKey(deviceId), fields);
            publishLive(deviceId);

            if (healthChanged) {
                dispatchHealth(deviceId, reasons, batteryReading, tempReading, envelopeTimestamp, record);
            }
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Falha projetando telemetry id={}: {}", record.getId(), e.getMessage());
        }
    }

    private void dispatchHealth(String deviceId, Set<String> reasons, Double batteryMv, Double tempC,
                                String envelopeTimestamp, MapRecord<String, String, String> record) {
        Map<String, Object> details = new HashMap<>();
        details.put("health", reasons.isEmpty() ? "OK" : "WARNING");
        details.put("reasons", String.join(",", reasons));
        if (batteryMv != null) details.put("batteryMv", batteryMv);
        if (tempC != null) details.put("tempC", tempC);

        DeviceEvent event = DeviceEvent.builder()
                .eventType(reasons.isEmpty() ? EventType.DEVICE_HEALTH_RECOVERED : EventType.DEVICE_HEALTH_WARNING)
                .deviceId(deviceId)
                .details(details)
                .timestamp(parseEpochMillis(envelopeTimestamp))
                .build();

        // prefixo evita colisao com ids do stream:status no dedupe do EventLog
        eventDispatcher.dispatch(event, "telemetry:" + record.getId().getValue());
    }

    private Set<String> parseReasons(Object raw) {
        Set<String> reasons = new TreeSet<>();
        if (raw == null) return reasons;
        for (String r : String.valueOf(raw).split(",")) {
            if (!r.isBlank()) reasons.add(r.trim());
        }
        return reasons;
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Instant parseEpochMillis(String value) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    // Payload inválido é erro permanente: ACK e descarta.
    // Retorna null quando descartou.
    private Map<String, Object> parseOrDiscard(String payload, MapRecord<String, String, String> record) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> dto = objectMapper.readValue(payload, Map.class);
            return dto;
        } catch (JsonProcessingException e) {
            log.warn("Payload inválido, descartando id={}: {}", record.getId(), e.getMessage());
            redisTemplate.opsForStream().acknowledge(GROUP, record);
            return null;
        }
    }

    private String hashKey(String deviceId) {
        return HASH_PREFIX + deviceId + HASH_SUFFIX;
    }

    // Traduz o estado operacional do firmware pro vocabulário de status do CMDB
    private String toCmdbStatus(DeviceState state) {
        return switch (state) {
            case OPERATIONAL, PROVISIONING_SUCCESS, OTA_SUCCESSFUL, BOOT_AUDIT, COMMAND_COMPLETE, FIRMWARE_ROLLBACK -> "ACTIVE";
            case OTA_FOUND, OTA_DOWNLOADING, REBOOTING -> "COMMAND_PENDING";
            case NVS_INIT, WIFI_AP_MODE, WIFI_CONNECTING, TIME_SYNC, PROVISIONING,
                 MQTT_WAITING_CONNECT, MQTT_INIT -> "PROVISIONING";
            case ERROR -> "ERROR";
            case CRITICAL_BATTERY -> "CRITICAL_BATTERY";
            default -> null;
        };
    }

    // Publica o estado completo agregado do device no channel Redis pub/sub.
    // govApi (SseController) assina esse channel e broadcast pros clientes SSE
    private void publishLive(String deviceId) {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(hashKey(deviceId));
            if (raw == null || raw.isEmpty()) return;

            Map<String, Object> payload = new HashMap<>();
            payload.put("deviceId", deviceId);
            if (raw.get("last_seen") != null) payload.put("lastSeen", raw.get("last_seen"));
            if (raw.get("status") != null) payload.put("status", raw.get("status"));
            if (raw.get("latitude") != null) payload.put("lat", raw.get("latitude"));
            if (raw.get("longitude") != null) payload.put("lon", raw.get("longitude"));
            if (raw.get("battery_mv") != null) payload.put("batteryMv", raw.get("battery_mv"));
            if (raw.get("battery_ts") != null) payload.put("batteryTs", raw.get("battery_ts"));
            if (raw.get("rssi") != null) payload.put("rssi", raw.get("rssi"));
            if (raw.get("rssi_ts") != null) payload.put("rssiTs", raw.get("rssi_ts"));
            if (raw.get("temp_c") != null) payload.put("tempC", raw.get("temp_c"));
            if (raw.get("temp_ts") != null) payload.put("tempTs", raw.get("temp_ts"));
            if (raw.get("health") != null) payload.put("health", raw.get("health"));
            if (raw.get("health_reason") != null) payload.put("healthReason", raw.get("health_reason"));

            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.convertAndSend(CHANNEL_DEVICE_LIVE, json);
        } catch (Exception e) {
            log.warn("Falha publicando live state device={}: {}", deviceId, e.getMessage());
        }
    }

    private String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void ensureGroup(String stream) {
        try {
            redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute("XGROUP",
                    "CREATE".getBytes(),
                    stream.getBytes(),
                    GROUP.getBytes(),
                    "$".getBytes(),        // só mensagens novas
                    "MKSTREAM".getBytes()
                )
            );
            log.info("Consumer group {} criado (MKSTREAM) em {}", GROUP, stream);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("BUSYGROUP")) {
                log.debug("Consumer group {} já existe em {}", GROUP, stream);
            } else {
                log.warn("Erro ao criar group {} em {}: {}", GROUP, stream, msg);
            }
        }
    }

    // Recupera mensagens presas no PEL (entregues sem ACK) dos dois streams.
    // Poison messages (> MAX_DELIVERIES) são descartadas com ACK.
    @Scheduled(fixedDelayString = "${app.streams.sweep-interval-ms:60000}", initialDelay = 15000)
    public void sweepPendingMessages() {
        sweepStream(STREAM_STATUS, this::handleStatus);
        sweepStream(STREAM_TELEMETRY, this::handleTelemetry);
    }

    private void sweepStream(String stream,
                             java.util.function.Consumer<MapRecord<String, String, String>> handler) {
        try {
            StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
            PendingMessages pending = ops.pending(stream, GROUP, Range.unbounded(), SWEEP_BATCH);
            if (pending == null || pending.isEmpty()) return;

            List<RecordId> toClaim = new ArrayList<>();
            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().compareTo(SWEEP_MIN_IDLE) < 0) continue;
                if (pm.getTotalDeliveryCount() > MAX_DELIVERIES) {
                    log.error("Msg {} descartada após {} entregas (poison)", pm.getId(), pm.getTotalDeliveryCount());
                    ops.acknowledge(stream, GROUP, pm.getId());
                    continue;
                }
                toClaim.add(pm.getId());
            }
            if (toClaim.isEmpty()) return;

            List<MapRecord<String, String, String>> claimed = ops.claim(stream, GROUP, consumerName,
                    XClaimOptions.minIdle(SWEEP_MIN_IDLE).ids(toClaim));

            // id pedido mas não retornado = entrada já trimada do stream (MAXLEN) — ACK limpa o PEL
            Set<RecordId> returned = new HashSet<>();
            for (MapRecord<String, String, String> record : claimed) {
                returned.add(record.getId());
            }
            for (RecordId id : toClaim) {
                if (!returned.contains(id)) ops.acknowledge(stream, GROUP, id);
            }

            claimed.forEach(handler);
            if (!claimed.isEmpty()) {
                log.info("PEL sweep: {} mensagens reivindicadas e reprocessadas em {}", claimed.size(), stream);
            }
        } catch (Exception e) {
            log.warn("PEL sweep falhou em {}: {}", stream, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (statusSubscription != null) statusSubscription.cancel();
        if (telemetrySubscription != null) telemetrySubscription.cancel();
        if (container != null) container.stop();
    }
}
