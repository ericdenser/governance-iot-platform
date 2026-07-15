package com.eric.governanceApi.governanceApi.service;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SseRegistry {
    
    private final ConcurrentHashMap<String, ClientContext> clients = new ConcurrentHashMap<>();

    public void register(String clientId, SseEmitter sseEmitter, Set<String> visibleDeviceIds) {
        ClientContext ctx = new ClientContext(sseEmitter, visibleDeviceIds);
        clients.put(clientId, ctx);
        sseEmitter.onCompletion(() -> {
            clients.remove(clientId);
            log.debug("SSE completed clientId={}", clientId);
        });

        sseEmitter.onTimeout(() -> {
            clients.remove(clientId);
            log.debug("SSE timeout clientId={}", clientId);
        });

        sseEmitter.onError(e -> {
            clients.remove(clientId);
            log.debug("SSE error clientId={}, {}", clientId, e.getMessage());
        });

        log.info("SSE registered clientId={} visibleDevices={}", clientId, visibleDeviceIds.size());
    }

    public void broadcast(String deviceId, String payloadJson) {
        for (var entry : clients.entrySet()) {
            ClientContext ctx = entry.getValue();
            if (!ctx.visibleDeviceIds().contains(deviceId)) continue;
            try {
                ctx.emitter().send(SseEmitter.event().name("device-live").data(payloadJson));
            } catch (IOException | IllegalStateException e) {
                clients.remove(entry.getKey());
                log.debug("SSE removed clientId={} on send failure", entry.getKey());
            }
        }
    }

    // Mantém conexões ociosas vivas 
    // e detecta clientes mortos mesmo sem tráfego de eventos.
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        for (var entry : clients.entrySet()) {
            try {
                entry.getValue().emitter().send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                clients.remove(entry.getKey());
                log.info("SSE removed clientId={} on heartbeat failure: {}", entry.getKey(), e.toString());
            }
        }
    }

    public int activeClients() {
        return clients.size();
    }


    public record ClientContext(SseEmitter emitter, Set<String> visibleDeviceIds){}
}
