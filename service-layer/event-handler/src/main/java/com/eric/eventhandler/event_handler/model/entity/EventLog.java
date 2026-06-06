package com.eric.eventhandler.event_handler.model.entity;

import java.time.LocalDateTime;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;


// Registrar os subscribers e seus eventos
@Entity
@Table(name = "event_log")
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

    @Column(length = 2000)
    private String payload;

    @Enumerated(EnumType.STRING)
    private DeviceState previousStatus;

    @Enumerated(EnumType.STRING)
    private DeviceState newStatus;

    private LocalDateTime timestamp = LocalDateTime.now();
}

