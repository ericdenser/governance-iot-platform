package com.eric.governanceApi.governanceApi.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class LiveStateSubscriber implements MessageListener{

    public static final String CHANNEL = "channel:device-live";

    private final SseRegistry sseRegistry;
    private final ObjectMapper objectMapper;
    private final DeviceRepository deviceRepository;

    // deviceId -> name. O payload do event-handler não carrega nome (Redis não
    // conhece o CMDB); enriquecemos aqui com 1 query por device novo.
    private final ConcurrentHashMap<String, String> nameCache = new ConcurrentHashMap<>();

    public LiveStateSubscriber(SseRegistry sseRegistry, ObjectMapper objectMapper,
                               DeviceRepository deviceRepository) {
        this.objectMapper = objectMapper;
        this.sseRegistry = sseRegistry;
        this.deviceRepository = deviceRepository;
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            String deviceId = (String) parsed.get("deviceId");
            if (deviceId == null) return;

            String name = resolveName(deviceId);
            if (name != null) parsed.put("name", name);

            sseRegistry.broadcast(deviceId, objectMapper.writeValueAsString(parsed));
        } catch (Exception e) {
            log.warn("Failed processing msg pub/sub: {}", e.getMessage());
        }
    }

    private String resolveName(String deviceId) {
        // "" = device sem nome/não encontrado (cacheia pra não martelar o banco)
        String cached = nameCache.computeIfAbsent(deviceId,
                id -> deviceRepository.findNameByDeviceId(id).orElse(""));
        return cached.isEmpty() ? null : cached;
    }

    // Bound pro staleness: rename de device reflete em até 10 min
    @Scheduled(fixedRate = 600_000)
    public void evictNameCache() {
        nameCache.clear();
    }
}
