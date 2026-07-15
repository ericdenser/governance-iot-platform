package com.eric.governanceApi.governanceApi.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.eric.governanceApi.governanceApi.repository.DeviceRepository;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SseRegistry {

    private final ConcurrentHashMap<String, ClientContext> clients = new ConcurrentHashMap<>();
    private final DeviceRepository deviceRepository;

    public SseRegistry(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public void register(String clientId, SseEmitter sseEmitter, String actorId,
                         boolean admin, Set<String> visibleDeviceIds) {
        ClientContext ctx = new ClientContext(sseEmitter, actorId, admin,
                new AtomicReference<>(visibleDeviceIds));
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

        log.info("SSE registered clientId={} admin={} visibleDevices={}",
                clientId, admin, visibleDeviceIds.size());
    }

    public void broadcast(String deviceId, String payloadJson) {
        for (var entry : clients.entrySet()) {
            ClientContext ctx = entry.getValue();
            if (!ctx.admin() && !ctx.visibleDeviceIds().get().contains(deviceId)) continue;
            try {
                ctx.emitter().send(SseEmitter.event().name("device-live").data(payloadJson));
            } catch (IOException | IllegalStateException e) {
                clients.remove(entry.getKey());
                log.debug("SSE removed clientId={} on send failure", entry.getKey());
            }
        }
    }

    // O snapshot RBAC é tirado no connect; sem refresh, device provisionado
    // depois da conexão ficaria invisível até o cliente reconectar (F5).
    // Admin não usa Set (vê tudo). Uma query por actor distinto, não por aba.
    @Scheduled(fixedRate = 60_000)
    public void refreshVisibility() {
        Map<String, Set<String>> byActor = new HashMap<>();
        for (ClientContext ctx : clients.values()) {
            if (ctx.admin() || ctx.actorId() == null) continue;
            Set<String> fresh = byActor.computeIfAbsent(ctx.actorId(),
                    id -> new HashSet<>(deviceRepository.findDeviceIdsByUserGroups(id)));
            ctx.visibleDeviceIds().set(fresh);
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


    public record ClientContext(SseEmitter emitter, String actorId, boolean admin,
                                AtomicReference<Set<String>> visibleDeviceIds){}
}
