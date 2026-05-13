package com.eric.eventhandler.event_handler.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import com.eric.eventhandler.event_handler.enums.EventType;

@Entity
@Table(name = "event_subscriptions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"subscriberName", "eventType"}))
@Data
@NoArgsConstructor
public class EventSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String subscriberName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private String webhookUrl;

    private boolean active = true;

    private LocalDateTime createdAt = LocalDateTime.now();
}