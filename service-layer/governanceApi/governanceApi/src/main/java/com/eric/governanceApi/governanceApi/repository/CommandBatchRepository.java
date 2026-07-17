package com.eric.governanceApi.governanceApi.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.model.entity.CommandBatch;

public interface CommandBatchRepository extends JpaRepository<CommandBatch, Long> {

    Optional<CommandBatch> findByBatchId(String batchId);

    Page<CommandBatch> findAllByOrderBySentAtDesc(Pageable pageable);

    // Batch visível se ao menos um record aponta pra device de grupo do usuário
    @Query(value = """
        SELECT DISTINCT b FROM CommandBatch b JOIN b.records c
        WHERE c.targetDevice.id IN (
            SELECT m.id.deviceId FROM DeviceGroupMembership m
            WHERE m.id.groupId IN (
                SELECT a.id.groupId FROM UserGroupAssignment a
                WHERE a.id.keycloakUserId = :keycloakUserId
            )
        )
        ORDER BY b.sentAt DESC
        """,
        countQuery = """
        SELECT COUNT(DISTINCT b) FROM CommandBatch b JOIN b.records c
        WHERE c.targetDevice.id IN (
            SELECT m.id.deviceId FROM DeviceGroupMembership m
            WHERE m.id.groupId IN (
                SELECT a.id.groupId FROM UserGroupAssignment a
                WHERE a.id.keycloakUserId = :keycloakUserId
            )
        )
        """)
    Page<CommandBatch> findAllByKeycloakUserId(@Param("keycloakUserId") String keycloakUserId, Pageable pageable);
}
