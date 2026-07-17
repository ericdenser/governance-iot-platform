package com.eric.governanceApi.governanceApi.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;

public interface CommandRecordRepository extends JpaRepository<CommandRecord, Long> {

    List<CommandRecord> findByStatusAndSentAtBefore(CommandStatus status, Instant threshold);

    Page<CommandRecord> findByTargetDevice_DeviceId(String deviceId, Pageable pageable);

    Optional<CommandRecord> findFirstByTargetDevice_DeviceIdAndStatus(String deviceId, CommandStatus status);

    List<CommandRecord> findByBatch_BatchIdOrderByIdAsc(String batchId);

    // Records do batch restritos aos devices dos grupos do usuário
    @Query("""
        SELECT c FROM CommandRecord c
        WHERE c.batch.batchId = :batchId
        AND c.targetDevice.id IN (
            SELECT m.id.deviceId FROM DeviceGroupMembership m
            WHERE m.id.groupId IN (
                SELECT a.id.groupId FROM UserGroupAssignment a
                WHERE a.id.keycloakUserId = :keycloakUserId
            )
        )
        ORDER BY c.id ASC
        """)
    List<CommandRecord> findByBatchIdVisibleTo(@Param("batchId") String batchId,
                                              @Param("keycloakUserId") String keycloakUserId);

    interface BatchStatusCount {
        Long getBatchId();
        CommandStatus getStatus();
        Long getTotal();
    }

    @Query("""
        SELECT c.batch.id AS batchId, c.status AS status, COUNT(c) AS total
        FROM CommandRecord c
        WHERE c.batch.id IN :batchIds
        GROUP BY c.batch.id, c.status
        """)
    List<BatchStatusCount> countStatusesByBatchIds(@Param("batchIds") List<Long> batchIds);
}
