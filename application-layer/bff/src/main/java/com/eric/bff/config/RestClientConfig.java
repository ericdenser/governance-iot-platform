package com.eric.bff.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    @Bean
    public RestClient restClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        return RestClient.builder()
            .requestInterceptor((request, body, execution) -> {
                log.info("--> {} {}", request.getMethod(), request.getURI());
                var response = execution.execute(request, body);
                log.info("<-- {} {}", response.getStatusCode(), request.getURI());
                return response;
            })
            .requestInterceptor(new TokenRelayInterceptor(authorizedClientManager))
            .build();
    }
}
