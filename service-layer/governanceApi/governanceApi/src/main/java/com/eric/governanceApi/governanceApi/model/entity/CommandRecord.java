package com.eric.governanceApi.governanceApi.model.entity;

import java.time.Instant;
import java.util.UUID;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
public class CommandRecord extends AuthoredEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", unique = true, nullable = false, updatable = false)
    private String commandId;

    // O Enum do comando enviado (ex: REBOOT, DEEP_SLEEP, UPDATE)
    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false)
    private DeviceCommands commandType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CommandStatus status = CommandStatus.PENDING;

    // Data de envio
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    // Data em que o ESP32 confirmou a execução (pode ser null se ainda estiver pendente)
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @JoinColumn(name = "event_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private Device targetDevice;


    @PrePersist
    private void generadeCommandID() {
        if (this.commandId == null) {
            this.commandId = UUID.randomUUID().toString();
        }
    }

}
