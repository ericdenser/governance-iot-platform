package com.eric.eventhandler.event_handler.service;

import java.util.Set;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.eric.eventhandler.event_handler.model.entity.DeviceEvent;
import com.eric.eventhandler.event_handler.model.entity.EventLog;
import com.eric.eventhandler.event_handler.repository.EventLogRepository;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;

// Responsável por registrar e dispachar o evento para os subscribers
@Service
@Slf4j
public class EventDispatcher {
    
    private final SubscriberRegistry registry;
    private final EventLogRepository eventLogRepository;
    private final RestClient client;
    private final ObjectMapper mapper;



    public EventDispatcher(SubscriberRegistry registry, EventLogRepository eventLogRepository,
        RestClient client, ObjectMapper mapper)
     {
        this.registry = registry;
        this.eventLogRepository = eventLogRepository;
        this.client = client;
        this.mapper = mapper;
    }

    public void dispatch(DeviceEvent event) {
        log.info("Dispachando evento: {}", event.getEventType());
        persistLog(event);
        deliverToSubscribers(event);
    }

    private void persistLog(DeviceEvent event) {
        
        try {
            EventLog newLog = new EventLog();

            newLog.setEventType(event.getEventType());
            newLog.setDeviceId(event.getDeviceId());
            newLog.setPayload(mapper.writeValueAsString(event));
            newLog.setPreviousStatus(event.getPreviousStatus());
            newLog.setNewStatus(event.getNewStatus());

            eventLogRepository.save(newLog);

            log.info("Log do evento: {} persistido com sucesso.", event.getEventType());
        } catch (Exception e) {
            log.error("Erro ao persistir log: {}" + e.getMessage());
        }
    }

    @Async
    private void deliverToSubscribers(DeviceEvent event) {
        log.info("Enviando evento: {} para subscribers.", event.getEventType());
        Set<String> subscribersUrl = registry.getWebhooksFor(event.getEventType());

        if (subscribersUrl.isEmpty()) {
            log.info("Nenhum assinante para {}", event.getEventType());
            return;
        }

        for (String url : subscribersUrl) {
            deliverWithRetry(url, event);
        }
    }


    private void deliverWithRetry(String url, DeviceEvent event) {

        int maxRetries = 5;
        log.info("Delivering event: {} to url -> {}", event.getEventType(), url);
        log.info("RAW PAYLOAD BEING SENT: {}", event);

        for (int attempts = 1; attempts <= maxRetries; attempts++) {
            try {
                client.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .header("X-Event-Type", event.getEventType().name())
                .body(event)
                .retrieve()
                .toBodilessEntity();

                log.info("Wehbook entregue: {} -> {} (tentativa {})", event.getEventType(), url, attempts);
                return;
            } catch(Exception e) {
                log.warn("Webhook falhou: {} → {} (tentativa {}/{}): {}",
                         event.getEventType(), url, attempts, maxRetries, e.getMessage());

            }
        }

    }

}
