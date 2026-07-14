package com.eric.eventhandler.event_handler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableAsync
public class AppConfig {

    @Value("${govapi.url}")
    private String govApiUrl;

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService clientService) {

        var provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, clientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public RestClient restClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        // adiciona token apenas em chamadas para a govApi; outros webhooks (externos) não recebem
        interceptor.setClientRegistrationIdResolver(request -> {
            String uri = request.getURI().toString();
            return uri.startsWith(govApiUrl) ? "govapi" : null;
        });

        return RestClient.builder()
                .requestInterceptor(interceptor)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            
            // Adicionando suporte a java.time.* (Instant, LocalDateTime, etc)
            .registerModule(new JavaTimeModule())
            
            // Formata datas como String ISO-8601 (ex: "2026-06-04T14:40:03.349Z") 
            // em vez de arrays de números timestamp [2026, 6, 4, 14, 40, ...]
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}