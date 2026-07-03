package com.eric.governanceApi.governanceApi.repository;

import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.projection.FirmwareListProjection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FirmwareRepository extends JpaRepository<Firmware, Long> {

    Optional<Firmware> findByProvisioningFirmwareTrue();

    Optional<Firmware> findByFirmwareId(String firmwareId);

    /** Non-admin: só firmwares dos grupos que o usuário pertence + plataforma. */
    @Query("""
    SELECT new com.eric.governanceApi.governanceApi.model.projection.FirmwareListProjection(
        f.firmwareId, f.firmwareName, f.description, f.ownerGroupId, f.provisioningFirmware,
        f.createdAt, f.createdByActorId, f.createdByUsername,
        (SELECT COUNT(v) FROM FirmwareVersion v WHERE v.firmware = f),
        latest.firmwareVersionId, latest.version, latest.status, latest.uploadedAt,
        latest.createdByUsername, latest.deployCount, latest.sizeBytes
    )
    FROM Firmware f
    LEFT JOIN FirmwareVersion latest ON latest.firmware = f
      AND latest.uploadedAt = (SELECT MAX(v2.uploadedAt) FROM FirmwareVersion v2 WHERE v2.firmware = f)
    WHERE f.ownerGroupId IS NULL OR f.ownerGroupId IN :groupIds
    """)
    List<FirmwareListProjection> findVisibleRowsByGroup(@Param("groupIds") List<String> groupIds);


    /** Admin: TODOS os firmwares. */
    @Query("""
    SELECT new com.eric.governanceApi.governanceApi.model.projection.FirmwareListProjection(
        f.firmwareId, f.firmwareName, f.description, f.ownerGroupId, f.provisioningFirmware,
        f.createdAt, f.createdByActorId, f.createdByUsername,
        (SELECT COUNT(v) FROM FirmwareVersion v WHERE v.firmware = f),
        latest.firmwareVersionId, latest.version, latest.status, latest.uploadedAt,
        latest.createdByUsername, latest.deployCount, latest.sizeBytes
    )
    FROM Firmware f
    LEFT JOIN FirmwareVersion latest ON latest.firmware = f
      AND latest.uploadedAt = (SELECT MAX(v2.uploadedAt) FROM FirmwareVersion v2 WHERE v2.firmware = f)
    """)
    List<FirmwareListProjection> findAllRowsAdmin();

    /**
     * Null-safe uniqueness check: (firmwareName, ownerGroupId) must be unique per scope.
     * Handles ownerGroupId=null (platform firmware) correctly via IS NULL.
     */
    @Query("""
        SELECT COUNT(f) > 0 FROM Firmware f
        WHERE f.firmwareName = :name
        AND (:ownerGroupId IS NOT NULL AND f.ownerGroupId = :ownerGroupId
             OR :ownerGroupId IS NULL AND f.ownerGroupId IS NULL)
        """)
    boolean existsByNameInScope(@Param("name") String name,
                                @Param("ownerGroupId") String ownerGroupId);
}
