package com.eric.eventhandler.event_handler.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.eventhandler.event_handler.enums.EventDeliveryStatus;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.entity.EventLog;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {

    List<EventLog> findByDeviceIdOrderByTimestampDesc(String device_id);

    List<EventLog> findByEventTypeOrderByTimestampDesc(EventType eventType);

    List<EventLog> findTop50ByOrderByTimestampDesc();

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByDeviceIdAndWebhookUrlAndDeliveryStatusIn(
        String deviceId, String webhookUrl, List<EventDeliveryStatus> statuses);

    // Retorna o evento mais antigo pendente/falhado por device+webhook (fila FIFO).
    @Query("""
        SELECT e FROM EventLog e
         WHERE e.deliveryStatus IN :statuses
           AND e.lastAttemptAt IS NOT NULL AND e.lastAttemptAt < :readyBefore
           AND NOT EXISTS (SELECT 1 FROM EventLog older
                             WHERE older.deviceId = e.deviceId
                               AND older.webhookUrl = e.webhookUrl
                               AND older.deliveryStatus IN :statuses
                               AND older.timestamp < e.timestamp)
         ORDER BY e.timestamp ASC
        """)
    List<EventLog> findRetryable(
        @Param("statuses") List<EventDeliveryStatus> statuses,
        @Param("readyBefore") Instant readyBefore,
        Pageable pageable);
}
