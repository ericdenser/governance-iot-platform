package com.eric.governanceApi.governanceApi.config;

import com.eric.governanceApi.governanceApi.enums.EventType;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class EventClient {

    private final RestClient restClient;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    @Value("${event.url}")
    private String eventBaseUrl;

    @Value("${govapi.self-url}")
    private String selfUrl;

    public EventClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 30_000)
    public void trySubscribe() {
        if (subscribed.get()) return;

        List<EventType> allEvents = Arrays.asList(EventType.values());
        Map<String, Object> request = Map.of(
            "subscriberName", "govApi",
            "eventType",      allEvents,
            "webhookUrl",     selfUrl + "/events/ingest"
        );

        try {
            restClient.post()
                    .uri(eventBaseUrl + "/subscribe")
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            subscribed.set(true);
            log.info("Inscrito no event-handler ({} eventos)", allEvents.size());
        } catch (Exception e) {
            log.warn("Event-handler indisponível, retentando em 30s: {}", e.getMessage());
        }
    }
}
