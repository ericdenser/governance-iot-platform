package com.eric.governanceApi.governanceApi.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment;
import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment.AssignmentId;

public interface UserGroupAssignmentRepository extends JpaRepository<UserGroupAssignment, AssignmentId> {

    List<UserGroupAssignment> findByGroupId(Long groupId);

    List<UserGroupAssignment> findByIdKeycloakUserId(String keycloakUserId);

    Optional<UserGroupAssignment> findByIdKeycloakUserIdAndGroupGroupId(String keycloakUserId, String groupId);

    boolean existsByIdKeycloakUserIdAndGroupGroupId(String keycloakUserId, String groupId);

    /** True if the user has one of the given roles in any group that contains the device. */
    @Query("""
        SELECT COUNT(a) FROM UserGroupAssignment a
        WHERE a.id.keycloakUserId = :uid
        AND a.role IN :roles
        AND a.id.groupId IN (
            SELECT m.id.groupId FROM DeviceGroupMembership m
            WHERE m.device.deviceId = :deviceId
        )
        """)
    long countByUserAndDeviceWithRoles(@Param("uid") String uid,
                                       @Param("deviceId") String deviceId,
                                       @Param("roles") Collection<GroupRole> roles);

    /** Returns the UUID groupId strings for all groups the user belongs to (any role). */
    @Query("SELECT a.group.groupId FROM UserGroupAssignment a WHERE a.id.keycloakUserId = :uid")
    List<String> findGroupUuidsByKeycloakUserId(@Param("uid") String uid);

    /** Returns the UUID groupId strings for groups the user belongs to with one of the given roles. */
    @Query("SELECT a.group.groupId FROM UserGroupAssignment a WHERE a.id.keycloakUserId = :uid AND a.role IN :roles")
    List<String> findGroupUuidsByKeycloakUserIdAndRoles(@Param("uid") String uid,
                                                        @Param("roles") Collection<GroupRole> roles);
}
