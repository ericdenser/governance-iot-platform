package com.eric.governanceApi.governanceApi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;

public interface EventRegistryRepository extends JpaRepository<EventRegistry, Long> {

    Page<EventRegistry> findByDevice_DeviceIdOrderByUploadedAtDesc(String deviceId, Pageable pageable);

    Page<EventRegistry> findAllByOrderByUploadedAtDesc(Pageable pageable);

    @Query("""
        SELECT e FROM EventRegistry e
        WHERE e.device.id IN (
            SELECT m.id.deviceId FROM DeviceGroupMembership m
            WHERE m.id.groupId IN (
                SELECT a.id.groupId FROM UserGroupAssignment a
                WHERE a.id.keycloakUserId = :keycloakUserId
            )
        )
        """)
    Page<EventRegistry> findAllByKeycloakUserId(@Param("keycloakUserId") String keycloakUserId, Pageable pageable);
}
