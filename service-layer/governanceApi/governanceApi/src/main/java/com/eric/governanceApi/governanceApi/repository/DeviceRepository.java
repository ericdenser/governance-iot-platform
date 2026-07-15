package com.eric.governanceApi.governanceApi.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.projection.DeviceIdNameProjection;
import com.eric.governanceApi.governanceApi.model.projection.DeviceSummaryProjection;

public interface DeviceRepository extends JpaRepository<Device, Long>{
    Optional<Device> findByMacAddress(String macAddress);

    @Query("SELECT d.certificate.certificatePem FROM Device d WHERE d.status = com.eric.governanceApi.governanceApi.enums.status.DeviceStatus.REVOKED")
    List<String> findAllRevokedCertificates();

    Optional<Device> findByDeviceId(String deviceId);

    @Query("SELECT d.deviceId FROM Device d")
    List<String> findAllDeviceIds();

    /** Non-admin: IDs de todos os devices dos grupos do actor. Sem paginação (mapa carrega tudo do escopo). */
    @Query("""
            SELECT d.deviceId FROM Device d
            WHERE d.id IN (
                SELECT m.device.id FROM DeviceGroupMembership m
                WHERE m.group.id IN (
                    SELECT a.group.id FROM UserGroupAssignment a
                    WHERE a.id.keycloakUserId = :actorId
                )
            )
            """)
    List<String> findDeviceIdsByUserGroups(@Param("actorId") String actorId);

    @Query("SELECT new com.eric.governanceApi.governanceApi.model.projection.DeviceIdNameProjection(d.deviceId, d.name) FROM Device d")
    List<DeviceIdNameProjection> findAllIdAndNames();

    @Query("""
            SELECT new com.eric.governanceApi.governanceApi.model.projection.DeviceIdNameProjection(d.deviceId, d.name)
            FROM Device d
            WHERE d.id IN (
                SELECT m.device.id FROM DeviceGroupMembership m
                WHERE m.group.id IN (
                    SELECT a.group.id FROM UserGroupAssignment a
                    WHERE a.id.keycloakUserId = :actorId
                )
            )
            """)
    List<DeviceIdNameProjection> findIdAndNamesByUserGroups(@Param("actorId") String actorId);

    /** Detail — carrega firmwareVersion e firmware em 1 query só. */
    @EntityGraph(attributePaths = {"firmwareVersion", "firmwareVersion.firmware", "sensorStatus"})
    Optional<Device> findWithFirmwareByDeviceId(String deviceId);

    /** Admin: TODOS os devices como projection, paginado, com filtros opcionais. */
    @Query(value = """
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
            WHERE (CAST(:search AS string) IS NULL
                   OR LOWER(d.name)       LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.deviceId)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.macAddress) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:status IS NULL OR d.status = :status)
            """,
            countQuery = """
            SELECT COUNT(d) FROM Device d
            WHERE (CAST(:search AS string) IS NULL
                   OR LOWER(d.name)       LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.deviceId)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.macAddress) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:status IS NULL OR d.status = :status)
            """)
    Page<DeviceSummaryProjection> findAllSummaries(@Param("search") String search,
                                                   @Param("status") DeviceStatus status,
                                                   Pageable pageable);

    /** Non-admin: só devices dos grupos que o usuário pertence, paginado, com filtros. */
    @Query(value = """
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
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(d.name)       LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.deviceId)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.macAddress) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:status IS NULL OR d.status = :status)
            """,
            countQuery = """
            SELECT COUNT(d) FROM Device d
            WHERE d.id IN (
                SELECT m.device.id FROM DeviceGroupMembership m
                WHERE m.group.id IN (
                    SELECT a.group.id FROM UserGroupAssignment a
                    WHERE a.id.keycloakUserId = :actorId
                )
            )
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(d.name)       LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.deviceId)   LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(d.macAddress) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:status IS NULL OR d.status = :status)
            """)
    Page<DeviceSummaryProjection> findSummariesByUserGroups(@Param("actorId") String actorId,
                                                            @Param("search") String search,
                                                            @Param("status") DeviceStatus status,
                                                            Pageable pageable);
}
