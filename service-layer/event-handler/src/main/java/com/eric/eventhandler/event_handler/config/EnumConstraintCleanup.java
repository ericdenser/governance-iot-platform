package com.eric.eventhandler.event_handler.config;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Remove o CHECK de enum do Hibernate, que o ddl-auto update nao atualiza quando o EventType ganha valor
@Component
@RequiredArgsConstructor
@Slf4j
public class EnumConstraintCleanup {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void dropEventTypeChecks() {
        List<String> statements = List.of(
            "ALTER TABLE IF EXISTS event_log DROP CONSTRAINT IF EXISTS event_log_event_type_check",
            "ALTER TABLE IF EXISTS event_subscriptions DROP CONSTRAINT IF EXISTS event_subscriptions_event_type_check"
        );
        for (String sql : statements) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("Falha removendo constraint de enum: {}", e.getMessage());
            }
        }
    }
}
