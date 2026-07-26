package com.eric.eventhandler.event_handler.model.entity;

import java.time.Instant;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventDeliveryStatus;
import com.eric.eventhandler.event_handler.enums.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "event_log", indexes = {
    @Index(name = "idx_eventlog_device_status_ts", columnList = "deviceId,deliveryStatus,timestamp"),
    @Index(name = "idx_eventlog_status_lastattempt", columnList = "deliveryStatus,lastAttemptAt")
})
@Data
@NoArgsConstructor
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private String deviceId;

    @Column(name = "source_message_id", length = 64)
    private String sourceMessageId;

    @Column(length = 2000)
    private String payload;

    @Column(length = 500)
    private String webhookUrl;

    @Enumerated(EnumType.STRING)
    private DeviceState previousStatus;

    @Enumerated(EnumType.STRING)
    private DeviceState newStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventDeliveryStatus deliveryStatus = EventDeliveryStatus.PENDING;

    @Column(nullable = false)
    private int attemptCount = 0;

    private Instant lastAttemptAt;

    @Column(length = 500)
    private String lastError;

    private Instant timestamp = Instant.now();
}
