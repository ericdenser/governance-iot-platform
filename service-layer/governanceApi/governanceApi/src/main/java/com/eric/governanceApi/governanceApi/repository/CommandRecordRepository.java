package com.eric.governanceApi.governanceApi.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;

public interface CommandRecordRepository extends JpaRepository<CommandRecord, Long> {
    // Busca todos os comandos com um status específico enviados ANTES da data informada
    List<CommandRecord> findByStatusAndSentAtBefore(CommandStatus status, Instant threshold);
}
