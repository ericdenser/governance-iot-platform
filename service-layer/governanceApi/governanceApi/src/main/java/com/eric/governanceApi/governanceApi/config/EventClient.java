package com.eric.governanceApi.governanceApi.config;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class EventClient {

    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    @Value("${event.url}")
    private String eventBaseUrl;

    @Value("${govapi.self-url}")
    private String selfUrl;

    public EventClient(RestClient restClient, OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = restClient;
        this.authorizedClientManager = authorizedClientManager;
    }

    private String fetchToken() {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId("gov-api")
                .principal("gov-api")
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new InfrastructureException("Não foi possível obter token OAuth2 para o event-handler");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }

    @PostConstruct
    public void subscribeToEvents() {
        List<EventType> allEvents = Arrays.asList(EventType.values());

        Map<String, Object> request = Map.of(
            "subscriberName", "govApi",
            "eventType",      allEvents,
            "webhookUrl",     selfUrl + "/events/ingest"
        );

        log.info("Inscrevendo-se nos eventos: {}", allEvents);

        try {
            String response = restClient.post()
                    .uri(eventBaseUrl + "/subscribe")
                    .header("Authorization", "Bearer " + fetchToken())
                    .body(request)
                    .retrieve()
                    .body(String.class);

            log.info("EventHandler respondeu: {}", response);

        } catch (ResourceAccessException e) {
            log.error("EventHandler offline: {}", e.getMessage());
            throw new InfrastructureException("EventHandler offline. Subscrição cancelada.");
        } catch (InfrastructureException e) {
            throw e;
        } catch (Exception e) {
            log.error("Falha ao comunicar com EventHandler: {}", e.getMessage());
            throw new InfrastructureException("Falha na comunicação com o EventHandler: " + e.getMessage());
        }
    }
}
