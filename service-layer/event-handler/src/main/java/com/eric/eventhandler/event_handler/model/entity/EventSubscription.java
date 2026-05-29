package com.eric.eventhandler.event_handler.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import com.eric.eventhandler.event_handler.enums.EventType;

@Entity
@Table(name = "event_subscriptions",
       // CORREÇÃO 1: A constraint agora é entre o ID do subscriber e o evento, e não mais o nome.
       uniqueConstraints = @UniqueConstraint(columnNames = {"subscriber_id", "eventType"}))
@Data
@NoArgsConstructor
public class EventSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscriber_id", nullable = false)
    private Subscriber subscriber; // CORREÇÃO 2: Faltava o modificador 'private' aqui

    // CORREÇÃO 3: O campo 'private String subscriberName;' foi APAGADO. 
    // O nome agora vive apenas dentro da classe Subscriber.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private String webhookUrl;

    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}