package com.eric.governanceApi.governanceApi.service;

import java.util.Map;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class LiveStateSubscriber implements MessageListener{

    public static final String CHANNEL = "channel:device-live";

    private final SseRegistry sseRegistry;
    private final ObjectMapper objectMapper;

    public LiveStateSubscriber(SseRegistry sseRegistry, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.sseRegistry = sseRegistry;
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            String deviceId = (String) parsed.get("deviceId");
            if (deviceId == null) return;
            sseRegistry.broadcast(deviceId, payload);
        } catch (Exception e) {
            log.warn("Failed processing msg pub/sub: {}", e.getMessage());
        }
    }
}
