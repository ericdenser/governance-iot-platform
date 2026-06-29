package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment;

public record GroupMemberResponseDTO(
        String keycloakUserId,
        GroupRole role,
        String assignedByActorId,
        String assignedByUsername,
        Instant assignedAt
) {
    public static GroupMemberResponseDTO from(UserGroupAssignment a) {
        return new GroupMemberResponseDTO(
                a.getId().getKeycloakUserId(),
                a.getRole(),
                a.getAssignedByActorId(),
                a.getAssignedByUsername(),
                a.getAssignedAt()
        );
    }
}
