package com.eric.eventhandler.event_handler.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.eric.eventhandler.event_handler.enums.EventDeliveryStatus;
import com.eric.eventhandler.event_handler.model.entity.DeviceEvent;
import com.eric.eventhandler.event_handler.model.entity.EventLog;
import com.eric.eventhandler.event_handler.repository.EventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

// FIFO por (deviceId, webhookUrl): se existe evento anterior pendente pro mesmo
// destino, o novo entra como QUEUED e só sai pelo EventRetryScheduler em ordem.
@Service
@Slf4j
public class EventDispatcher {

    private static final int INITIAL_ATTEMPTS = 3;
    private static final long[] INITIAL_BACKOFF_MS = { 1_000L, 3_000L, 10_000L };
    private static final List<EventDeliveryStatus> BLOCKING_STATES =
        List.of(EventDeliveryStatus.QUEUED, EventDeliveryStatus.FAILED);

    private final SubscriberRegistry registry;
    private final EventLogRepository eventLogRepository;
    private final RestClient client;
    private final ObjectMapper mapper;

    public EventDispatcher(SubscriberRegistry registry, EventLogRepository eventLogRepository,
                           RestClient client, ObjectMapper mapper) {
        this.registry = registry;
        this.eventLogRepository = eventLogRepository;
        this.client = client;
        this.mapper = mapper;
    }

    public void dispatch(DeviceEvent event, String messageId) {
        log.info("Dispatch: {} device={}", event.getEventType(), event.getDeviceId());

        Set<String> subscribersUrl = registry.getWebhooksFor(event.getEventType());
        if (subscribersUrl.isEmpty()) {
            persistLog(event, messageId, null, EventDeliveryStatus.DELIVERED, "no subscribers");
            return;
        }

        for (String url : subscribersUrl) {
            boolean hasEarlierPending = eventLogRepository
                .existsByDeviceIdAndWebhookUrlAndDeliveryStatusIn(
                    event.getDeviceId(), url, BLOCKING_STATES);

            EventLog logEntry = persistLog(event, messageId, url,
                hasEarlierPending ? EventDeliveryStatus.QUEUED : EventDeliveryStatus.PENDING,
                null);

            if (!hasEarlierPending) {
                deliverImmediate(logEntry, event, url);
            } else {
                log.info("Evento enfileirado (aguardando anterior): device={} type={}",
                         event.getDeviceId(), event.getEventType());
            }
        }
    }

    private EventLog persistLog(DeviceEvent event, String messageId, String url,
                                EventDeliveryStatus initialStatus, String note) {
        try {
            EventLog entry = new EventLog();
            entry.setEventType(event.getEventType());
            entry.setDeviceId(event.getDeviceId());
            entry.setPayload(mapper.writeValueAsString(event));
            entry.setPreviousStatus(event.getPreviousStatus());
            entry.setNewStatus(event.getNewStatus());
            entry.setSourceMessageId(messageId);
            entry.setWebhookUrl(url);
            entry.setDeliveryStatus(initialStatus);
            if (note != null) entry.setLastError(note);
            return eventLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Falha ao persistir EventLog: {}", e.getMessage());
            return null;
        }
    }

    @Transactional
    public void deliverImmediate(EventLog logEntry, DeviceEvent event, String url) {
        Exception lastEx = null;

        for (int i = 0; i < INITIAL_ATTEMPTS; i++) {
            try {
                client.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("X-Event-Type", event.getEventType().name())
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();

                if (logEntry != null) {
                    logEntry.setDeliveryStatus(EventDeliveryStatus.DELIVERED);
                    logEntry.setAttemptCount(logEntry.getAttemptCount() + i + 1);
                    logEntry.setLastAttemptAt(Instant.now());
                    logEntry.setLastError(null);
                    eventLogRepository.save(logEntry);
                }
                log.info("Webhook OK: {} -> {} (attempt {})",
                         event.getEventType(), url, i + 1);
                return;
            } catch (Exception e) {
                lastEx = e;
                log.warn("Webhook falhou: {} -> {} (attempt {}/{}): {}",
                         event.getEventType(), url, i + 1, INITIAL_ATTEMPTS, e.getMessage());
                sleep(INITIAL_BACKOFF_MS[i]);
            }
        }

        if (logEntry != null) {
            logEntry.setDeliveryStatus(EventDeliveryStatus.FAILED);
            logEntry.setAttemptCount(logEntry.getAttemptCount() + INITIAL_ATTEMPTS);
            logEntry.setLastAttemptAt(Instant.now());
            logEntry.setLastError(lastEx != null ? truncate(lastEx.getMessage(), 500) : "unknown");
            eventLogRepository.save(logEntry);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
