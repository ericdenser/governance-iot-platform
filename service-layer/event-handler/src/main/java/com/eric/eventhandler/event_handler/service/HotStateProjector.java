package com.eric.eventhandler.event_handler.service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Service;

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

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription statusSubscription;
    private Subscription telemetrySubscription;
    private String consumerName;

    public HotStateProjector(RedisConnectionFactory connectionFactory,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        ensureGroup(STREAM_STATUS);
        ensureGroup(STREAM_TELEMETRY);
        consumerName = buildConsumerName();

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
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> dto = objectMapper.readValue(payload, Map.class);

            String deviceId = strOrNull(dto.get("deviceId"));
            if (deviceId == null) {
                redisTemplate.opsForStream().acknowledge(GROUP, record);
                return;
            }

            Map<String, String> fields = new HashMap<>();
            fields.put("last_seen", envelopeTimestamp);
            Object status = dto.get("status");
            if (status != null) fields.put("status", String.valueOf(status));

            redisTemplate.opsForHash().putAll(hashKey(deviceId), fields);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Falha projetando status id={}: {}", record.getId(), e.getMessage());
            // sem ACK -> reentrega
        }
    }

    private void handleTelemetry(MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        String envelopeTimestamp = record.getValue().get("timestamp");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> dto = objectMapper.readValue(payload, Map.class);

            String deviceId = strOrNull(dto.get("deviceId"));
            if (deviceId == null) {
                redisTemplate.opsForStream().acknowledge(GROUP, record);
                return;
            }

            Map<String, String> fields = new HashMap<>();
            fields.put("last_seen", envelopeTimestamp);

            // TelemetryDTO.readings = Map<String, Float> — extrai lat/lon se presentes
            Object readings = dto.get("readings");
            if (readings instanceof Map<?, ?> map) {
                Object lat = map.get("lat");
                Object lon = map.get("lon");
                if (lat != null) fields.put("lat", String.valueOf(lat));
                if (lon != null) fields.put("lon", String.valueOf(lon));
            }

            redisTemplate.opsForHash().putAll(hashKey(deviceId), fields);
            redisTemplate.opsForStream().acknowledge(GROUP, record);
        } catch (Exception e) {
            log.error("Falha projetando telemetry id={}: {}", record.getId(), e.getMessage());
        }
    }

    private String hashKey(String deviceId) {
        return HASH_PREFIX + deviceId + HASH_SUFFIX;
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

    private String buildConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "consumer-" + UUID.randomUUID();
        }
    }

    @PreDestroy
    public void stop() {
        if (statusSubscription != null) statusSubscription.cancel();
        if (telemetrySubscription != null) telemetrySubscription.cancel();
        if (container != null) container.stop();
    }
}
