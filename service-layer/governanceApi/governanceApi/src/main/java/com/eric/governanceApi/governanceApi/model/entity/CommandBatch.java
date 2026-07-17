package com.eric.governanceApi.governanceApi.model.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Agrupa os CommandRecords de uma única ação (ex: 1 UPDATE para N devices).
// O status agregado não é armazenado: é derivado dos records na listagem.
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "command_batch")
public class CommandBatch extends AuthoredEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", unique = true, nullable = false, updatable = false)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false)
    private DeviceCommands commandType;

    // UUID da versão alvo (só UPDATE)
    @Column(name = "target_version_id", length = 36)
    private String targetVersionId;

    // Versão legível (ex: "2.0") denormalizada pra listagem sem join
    @Column(name = "target_version_label", length = 100)
    private String targetVersionLabel;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    // deviceIds solicitados que não existem no CMDB (CSV) — não podem ter record
    @Column(name = "not_found_ids", columnDefinition = "TEXT")
    private String notFoundIds;

    @OneToMany(mappedBy = "batch")
    private List<CommandRecord> records = new ArrayList<>();

    @PrePersist
    private void generateBatchId() {
        if (this.batchId == null) {
            this.batchId = UUID.randomUUID().toString();
        }
    }
}
