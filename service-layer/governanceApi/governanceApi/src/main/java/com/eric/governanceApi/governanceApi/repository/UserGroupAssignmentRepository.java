package com.eric.governanceApi.governanceApi.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment;
import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment.AssignmentId;

public interface UserGroupAssignmentRepository extends JpaRepository<UserGroupAssignment, AssignmentId> {

    List<UserGroupAssignment> findByGroupId(Long groupId);

    List<UserGroupAssignment> findByIdKeycloakUserId(String keycloakUserId);

    Optional<UserGroupAssignment> findByIdKeycloakUserIdAndGroupGroupId(String keycloakUserId, String groupId);

    boolean existsByIdKeycloakUserIdAndGroupGroupId(String keycloakUserId, String groupId);
}
