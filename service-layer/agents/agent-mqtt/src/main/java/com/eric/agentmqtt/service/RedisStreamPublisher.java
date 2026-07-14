package com.eric.agentmqtt.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

// Producer central de mensagens em Redis Streams.
@Service
@Slf4j
public class RedisStreamPublisher {

    public static final String STREAM_STATUS = "stream:status";
    public static final String STREAM_TELEMETRY = "stream:telemetry";
    public static final String STREAM_ERROR = "stream:error";

    private static final long MAXLEN_APPROX = 100_000L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;


    public RedisStreamPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public RecordId publish(String stream, String deviceId, String type, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Falha ao serializar payload pra stream {}: {}", stream, e.getMessage());
            return null;
        }

        Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("deviceId", deviceId == null ? "" : deviceId);
        envelope.put("timestamp", String.valueOf(System.currentTimeMillis()));
        envelope.put("type", type);
        envelope.put("payload", payloadJson);

        MapRecord<String, String, String> record = StreamRecords
                .mapBacked(envelope)
                .withStreamKey(stream);

        try {
            XAddOptions options = XAddOptions.maxlen(MAXLEN_APPROX).approximateTrimming(true);
            RecordId id = redisTemplate.opsForStream().add(record, options);
            log.debug("XADD {} id={} device={}", stream, id, deviceId);
            return id;
        } catch (Exception e) {
            log.error("Falha em XADD {} device={}: {}", stream, deviceId, e.getMessage());
            return null;
        }
    }
}
