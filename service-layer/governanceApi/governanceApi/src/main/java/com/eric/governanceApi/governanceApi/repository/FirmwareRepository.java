package com.eric.governanceApi.governanceApi.repository;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FirmwareRepository extends JpaRepository<Firmware, Long> {

    Optional<Firmware> findByProvisioningFirmwareTrue();

    List<Firmware> findByStatusOrderByVersionDesc(FirmwareStatus status);

    List<Firmware> findAllByOrderByVersionDesc();

    Optional<Firmware> findByFirmwareId(String firmwareId);

    List<Firmware> findByOwnerGroupIdIsNull();

    /**
     * Null-safe uniqueness check: (version, ownerGroupId) must be unique per scope.
     * Handles ownerGroupId=null (platform firmware) correctly via IS NULL.
     */
    @Query("""
        SELECT COUNT(f) > 0 FROM Firmware f
        WHERE f.version = :version
        AND (:ownerGroupId IS NOT NULL AND f.ownerGroupId = :ownerGroupId
             OR :ownerGroupId IS NULL AND f.ownerGroupId IS NULL)
        """)
    boolean existsByVersionInScope(@Param("version") String version,
                                   @Param("ownerGroupId") String ownerGroupId);

    /**
     * Null-safe version lookup within an ownership scope.
     * Used by event handlers to resolve firmware without ambiguity.
     */
    @Query("""
        SELECT f FROM Firmware f
        WHERE f.version = :version
        AND (:ownerGroupId IS NOT NULL AND f.ownerGroupId = :ownerGroupId
             OR :ownerGroupId IS NULL AND f.ownerGroupId IS NULL)
        """)
    Optional<Firmware> findByVersionInScope(@Param("version") String version,
                                            @Param("ownerGroupId") String ownerGroupId);

    @Query("""
        SELECT f FROM Firmware f
        WHERE f.ownerGroupId IS NULL OR f.ownerGroupId IN :groupIds
        ORDER BY f.version DESC
        """)
    List<Firmware> findVisibleForUser(@Param("groupIds") List<String> groupIds);
}
