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

    Page<CommandRecord> findAllByOrderBySentAtDesc(Pageable pageable);

    @Query("""
        SELECT c FROM CommandRecord c
        WHERE c.targetDevice.id IN (
            SELECT m.id.deviceId FROM DeviceGroupMembership m
            WHERE m.id.groupId IN (
                SELECT a.id.groupId FROM UserGroupAssignment a
                WHERE a.id.keycloakUserId = :keycloakUserId
            )
        )
        """)
    Page<CommandRecord> findAllByKeycloakUserId(@Param("keycloakUserId") String keycloakUserId, Pageable pageable);
}
