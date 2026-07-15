package com.eric.governanceApi.governanceApi.controller;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.eric.governanceApi.governanceApi.service.DeviceService;
import com.eric.governanceApi.governanceApi.service.SseRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/realtime")
public class SseController {
    
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30min

    private final SseRegistry sseRegistry;
    private final DeviceService deviceService;

    public SseController(DeviceService deviceService, SseRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
        this.deviceService = deviceService;

    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) 
        public SseEmitter stream(@RequestParam(required = false, defaultValue = "map") String scope) {
            Set<String> visibleDeviceIds = deviceService.getAccessibleDeviceIds();
            SseEmitter sseEmitter = new SseEmitter(EMITTER_TIMEOUT_MS);
            String clientId = UUID.randomUUID().toString();
            sseRegistry.register(clientId, sseEmitter, visibleDeviceIds);

            // Evento inicial força o flush dos headers: sem ele, com zero devices
            // o stream fica mudo e o cliente não sabe se a conexão foi aceita.
            try {
                sseEmitter.send(SseEmitter.event().name("connected")
                        .data("{\"clientId\":\"" + clientId + "\",\"devices\":" + visibleDeviceIds.size() + "}"));
                log.info("SSE evento connected enfileirado clientId={}", clientId);
            } catch (Exception e) {
                log.warn("Falha ao enviar evento inicial clientId={}: {}", clientId, e.toString());
            }

            log.info("SSE client conectado clientId={} scope={} devices={}", clientId, scope, visibleDeviceIds.size());
            return sseEmitter;
    }
}

