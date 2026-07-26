package com.eric.eventhandler.event_handler.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.eric.eventhandler.event_handler.enums.EventDeliveryStatus;
import com.eric.eventhandler.event_handler.model.entity.DeviceEvent;
import com.eric.eventhandler.event_handler.model.entity.EventLog;
import com.eric.eventhandler.event_handler.repository.EventLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

// Redispacha EventLogs FAILED/QUEUED em ordem FIFO por (device,webhook).
// Backoff exponencial pelos attempts (2m,4m,8m,16m,32m). Abandona após 8 tentativas totais.
@Service
@Slf4j
public class EventRetryScheduler {

    private static final int MAX_ATTEMPTS = 8;
    private static final int BATCH = 50;
    private static final List<EventDeliveryStatus> RETRYABLE =
        List.of(EventDeliveryStatus.FAILED, EventDeliveryStatus.QUEUED);

    private final EventLogRepository repo;
    private final RestClient client;
    private final ObjectMapper mapper;

    public EventRetryScheduler(EventLogRepository repo, RestClient client, ObjectMapper mapper) {
        this.repo = repo;
        this.client = client;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${webhook.retry-interval-ms:60000}")
    public void redispatch() {
        Instant now = Instant.now();
        List<EventLog> pending = repo.findRetryable(RETRYABLE, now, PageRequest.of(0, BATCH));
        if (pending.isEmpty()) return;

        int delivered = 0, stillFailed = 0, abandoned = 0;
        for (EventLog e : pending) {
            if (!isReady(e, now)) continue;
            switch (attempt(e)) {
                case DELIVERED -> delivered++;
                case FAILED -> stillFailed++;
                case ABANDONED -> abandoned++;
                default -> {}
            }
        }
        if (delivered + stillFailed + abandoned > 0) {
            log.info("Retry sweep: delivered={} failed={} abandoned={}",
                     delivered, stillFailed, abandoned);
        }
    }

    private boolean isReady(EventLog e, Instant now) {
        if (e.getDeliveryStatus() == EventDeliveryStatus.QUEUED) return true;
        Instant last = e.getLastAttemptAt();
        if (last == null) return true;
        long minutes = (long) Math.pow(2, Math.min(e.getAttemptCount(), 5));
        return Duration.between(last, now).toMinutes() >= minutes;
    }

    @Transactional
    private EventDeliveryStatus attempt(EventLog entry) {
        DeviceEvent event;
        try {
            event = mapper.readValue(entry.getPayload(), DeviceEvent.class);
        } catch (IOException ex) {
            entry.setDeliveryStatus(EventDeliveryStatus.ABANDONED);
            entry.setLastError("payload parse failed: " + ex.getMessage());
            repo.save(entry);
            return EventDeliveryStatus.ABANDONED;
        }

        try {
            client.post()
                .uri(entry.getWebhookUrl())
                .header("Content-Type", "application/json")
                .header("X-Event-Type", event.getEventType().name())
                .body(event)
                .retrieve()
                .toBodilessEntity();

            entry.setDeliveryStatus(EventDeliveryStatus.DELIVERED);
            entry.setAttemptCount(entry.getAttemptCount() + 1);
            entry.setLastAttemptAt(Instant.now());
            entry.setLastError(null);
            repo.save(entry);
            return EventDeliveryStatus.DELIVERED;
        } catch (Exception ex) {
            entry.setAttemptCount(entry.getAttemptCount() + 1);
            entry.setLastAttemptAt(Instant.now());
            entry.setLastError(truncate(ex.getMessage(), 500));
            if (entry.getAttemptCount() >= MAX_ATTEMPTS) {
                entry.setDeliveryStatus(EventDeliveryStatus.ABANDONED);
                repo.save(entry);
                return EventDeliveryStatus.ABANDONED;
            }
            entry.setDeliveryStatus(EventDeliveryStatus.FAILED);
            repo.save(entry);
            return EventDeliveryStatus.FAILED;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
