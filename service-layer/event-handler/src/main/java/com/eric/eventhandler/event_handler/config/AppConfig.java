package com.eric.eventhandler.event_handler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature; // Importar
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule; // Importar

@Configuration
@EnableAsync
public class AppConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            // Configuração original que você já tinha:
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            
            // Adicionando suporte a java.time.* (Instant, LocalDateTime, etc)
            .registerModule(new JavaTimeModule())
            
            // Formata datas como String ISO-8601 (ex: "2026-06-04T14:40:03.349Z") 
            // em vez de arrays de números timestamp [2026, 6, 4, 14, 40, ...]
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}