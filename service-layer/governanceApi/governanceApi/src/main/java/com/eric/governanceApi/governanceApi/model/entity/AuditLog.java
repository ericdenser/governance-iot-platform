package com.eric.governanceApi.governanceApi.model.entity;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.AuditAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Keycloak subject UUID — stable user identity. Null for M2M calls. */
    @Column(name = "actor_id", length = 36)
    private String actorId;

    /** Display name extracted from preferred_username claim. */
    @Column(name = "actor_username", length = 150)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 60)
    private AuditAction action;

    @Column(name = "target_type", length = 60)
    private String targetType;

    /** The main entity ID affected (firmwareId, deviceId, etc.). */
    @Column(name = "target_id", length = 255)
    private String targetId;

    /** JSON-serialized relevant arguments, excluding binary content. */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    @PrePersist
    private void prePersist() {
        if (performedAt == null) performedAt = Instant.now();
    }
}
