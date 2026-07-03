package com.eric.governanceApi.governanceApi.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.projection.DeployableVersionProjection;

@Repository
public interface FirmwareVersionRepository extends JpaRepository<FirmwareVersion, Long> {

    Optional<FirmwareVersion> findByFirmwareVersionId(String versionId);

    // impedir duplicada
    boolean existsByFirmware_IdAndVersion(Long firmwareId, String version);

    // todas versões de um firmware
    List<FirmwareVersion> findByFirmware_FirmwareIdOrderByUploadedAtDesc(String firmwareId);

    // versão mais recente
    Optional<FirmwareVersion> findFirstByFirmware_FirmwareIdOrderByUploadedAtDesc(String firmwareId);

    // versão mais recente do provisioning
    Optional<FirmwareVersion> findFirstByFirmware_ProvisioningFirmwareTrueOrderByUploadedAtDesc();

    // versão específica de um firmware, usada pelos EventHandlers e service
    Optional<FirmwareVersion> findByFirmware_FirmwareIdAndVersion(String firmwareId, String version);

    /** Admin: todas as versões não-deprecated de todos os firmwares. */
    @Query("""
        SELECT new com.eric.governanceApi.governanceApi.model.projection.DeployableVersionProjection(
            v.firmwareVersionId, v.version,
            v.firmware.firmwareId, v.firmware.firmwareName,
            v.firmware.ownerGroupId, v.status, v.uploadedAt
        )
        FROM FirmwareVersion v
        WHERE v.status <> com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus.DEPRECATED
        ORDER BY v.firmware.firmwareName, v.uploadedAt DESC
        """)
    List<DeployableVersionProjection> findAllDeployableAdmin();

    /** Non-admin: só versões dos firmwares que o usuário enxerga (plataforma ou grupos dele). */
    @Query("""
        SELECT new com.eric.governanceApi.governanceApi.model.projection.DeployableVersionProjection(
            v.firmwareVersionId, v.version,
            v.firmware.firmwareId, v.firmware.firmwareName,
            v.firmware.ownerGroupId, v.status, v.uploadedAt
        )
        FROM FirmwareVersion v
        WHERE v.status <> com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus.DEPRECATED
        AND (v.firmware.ownerGroupId IS NULL OR v.firmware.ownerGroupId IN :groupIds)
        ORDER BY v.firmware.firmwareName, v.uploadedAt DESC
        """)
    List<DeployableVersionProjection> findDeployableByGroups(@Param("groupIds") Collection<String> groupIds);

}
