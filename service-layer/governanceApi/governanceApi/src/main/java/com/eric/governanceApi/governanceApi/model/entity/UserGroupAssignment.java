package com.eric.governanceApi.governanceApi.model.entity;

import java.io.Serializable;
import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.GroupRole;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_group_assignment")
@Getter
@Setter
@NoArgsConstructor
public class UserGroupAssignment {

    @EmbeddedId
    private AssignmentId id = new AssignmentId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("groupId")
    @JoinColumn(name = "group_id")
    private DeviceGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupRole role;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by_actor_id", updatable = false, length = 36)
    private String assignedByActorId;

    @Column(name = "assigned_by_username", updatable = false, length = 150)
    private String assignedByUsername;

    @PrePersist
    private void prePersist() {
        if (assignedAt == null) assignedAt = Instant.now();
    }

    public UserGroupAssignment(String keycloakUserId, DeviceGroup group, GroupRole role,
                                String assignedByActorId, String assignedByUsername) {
        this.id.keycloakUserId = keycloakUserId;
        this.id.groupId = group.getId();
        this.group = group;
        this.role = role;
        this.assignedByActorId = assignedByActorId;
        this.assignedByUsername = assignedByUsername;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class AssignmentId implements Serializable {
        @Column(name = "keycloak_user_id", length = 36)
        private String keycloakUserId;

        @Column(name = "group_id")
        private Long groupId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AssignmentId other)) return false;
            return java.util.Objects.equals(keycloakUserId, other.keycloakUserId)
                && java.util.Objects.equals(groupId, other.groupId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(keycloakUserId, groupId);
        }
    }
}
