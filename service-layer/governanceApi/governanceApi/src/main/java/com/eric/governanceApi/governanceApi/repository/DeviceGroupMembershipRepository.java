package com.eric.governanceApi.governanceApi.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroupMembership;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroupMembership.MembershipId;

public interface DeviceGroupMembershipRepository extends JpaRepository<DeviceGroupMembership, MembershipId> {

    List<DeviceGroupMembership> findByGroupId(Long groupId);

    boolean existsByDeviceDeviceIdAndGroupGroupId(String deviceId, String groupId);

    Optional<DeviceGroupMembership> findByDeviceDeviceIdAndGroupGroupId(String deviceId, String groupId);

    @Query("""
        SELECT m.device FROM DeviceGroupMembership m
        WHERE m.group.id IN (
            SELECT a.id.groupId FROM UserGroupAssignment a
            WHERE a.id.keycloakUserId = :keycloakUserId
        )
        """)
    List<Device> findDevicesByKeycloakUserId(@Param("keycloakUserId") String keycloakUserId);

    @Query("""
        SELECT COUNT(m) FROM DeviceGroupMembership m
        WHERE m.device.deviceId = :deviceId
        AND m.id.groupId IN (
            SELECT a.id.groupId FROM UserGroupAssignment a
            WHERE a.id.keycloakUserId = :keycloakUserId
        )
        """)
    long countAccessibleByDeviceAndUser(@Param("deviceId") String deviceId,
                                        @Param("keycloakUserId") String keycloakUserId);

    List<DeviceGroupMembership> findByDeviceId(Long deviceId);

    void deleteByDeviceId(Long deviceId);
}
