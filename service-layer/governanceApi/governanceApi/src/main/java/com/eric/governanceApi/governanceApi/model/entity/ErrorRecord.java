package com.eric.governanceApi.governanceApi.model.entity;

import java.time.Instant;
import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;

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
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
public class ErrorRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_name", nullable = false)
    private DeviceError error;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ErrorStatus status = ErrorStatus.PENDING;

    // Data de envio
    @Column(name = "sent_at", nullable = false)
    private Instant reportedAt = Instant.now();

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    // Data em que o ESP32 confirmou a execução (pode ser null se ainda estiver pendente)
    @Column(name = "completed_at")
    private Instant fixedAt;

    @Column(name = "error_details", length = 500)
    private String details;

    @JoinColumn(name = "device_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private Device device;

}
