package com.eric.governanceApi.governanceApi.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.projection.DeviceSummaryProjection;

public interface DeviceRepository extends JpaRepository<Device, Long>{
    Optional<Device> findByMacAddress(String macAddress);

    @Query("SELECT d.certificate.certificatePem FROM Device d WHERE d.status = com.eric.governanceApi.governanceApi.enums.status.DeviceStatus.REVOKED")
    List<String> findAllRevokedCertificates();

    Optional<Device> findByDeviceId(String deviceId);

    /** Detail — carrega firmwareVersion e firmware em 1 query só. */
    @EntityGraph(attributePaths = {"firmwareVersion", "firmwareVersion.firmware"})
    Optional<Device> findWithFirmwareByDeviceId(String deviceId);

    /** Admin: TODOS os devices como projection. */
    @Query("""
            SELECT new com.eric.governanceApi.governanceApi.model.projection.DeviceSummaryProjection (
                d.deviceId, d.name, d.status, d.macAddress,
                fw.firmwareId,
                fw.firmwareName,
                v.firmwareVersionId,
                v.version,
                d.createdAt, d.lastSeen, d.issuedByActorId, d.issuedByUsername)
            FROM Device d
            LEFT JOIN d.firmwareVersion v
            LEFT JOIN v.firmware fw
            """)
    List<DeviceSummaryProjection> findAllSummaries();

    /** Non-admin: só devices dos grupos que o usuário pertence. */
    @Query("""
            SELECT new com.eric.governanceApi.governanceApi.model.projection.DeviceSummaryProjection (
                d.deviceId, d.name, d.status, d.macAddress,
                fw.firmwareId,
                fw.firmwareName,
                v.firmwareVersionId,
                v.version,
                d.createdAt, d.lastSeen, d.issuedByActorId, d.issuedByUsername)
            FROM Device d
            LEFT JOIN d.firmwareVersion v
            LEFT JOIN v.firmware fw
            WHERE d.id IN (
                SELECT m.device.id FROM DeviceGroupMembership m
                WHERE m.group.id IN (
                    SELECT a.group.id FROM UserGroupAssignment a
                    WHERE a.id.keycloakUserId = :actorId
                )
            )
            """)
    List<DeviceSummaryProjection> findSummariesByUserGroups(@Param("actorId") String actorId);
}
