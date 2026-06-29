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

    /** Returns all devices accessible to a given Keycloak user via their group assignments. */
    @Query("""
        SELECT m.device FROM DeviceGroupMembership m
        WHERE m.group.id IN (
            SELECT a.id.groupId FROM UserGroupAssignment a
            WHERE a.id.keycloakUserId = :keycloakUserId
        )
        """)
    List<Device> findDevicesByKeycloakUserId(@Param("keycloakUserId") String keycloakUserId);

    /** Returns all groups a device belongs to, by device's internal ID. */
    List<DeviceGroupMembership> findByDeviceId(Long deviceId);
}
