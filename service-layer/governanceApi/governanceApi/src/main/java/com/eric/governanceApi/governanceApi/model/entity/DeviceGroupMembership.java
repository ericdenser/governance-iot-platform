package com.eric.governanceApi.governanceApi.model.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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
@Table(name = "device_group_membership")
@Getter
@Setter
@NoArgsConstructor
public class DeviceGroupMembership {

    @EmbeddedId
    private MembershipId id = new MembershipId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("deviceId")
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("groupId")
    @JoinColumn(name = "group_id")
    private DeviceGroup group;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "added_by_actor_id", updatable = false, length = 36)
    private String addedByActorId;

    @Column(name = "added_by_username", updatable = false, length = 150)
    private String addedByUsername;

    @PrePersist
    private void prePersist() {
        if (addedAt == null) addedAt = Instant.now();
    }

    public DeviceGroupMembership(Device device, DeviceGroup group, String actorId, String username) {
        this.device = device;
        this.group = group;
        this.id.deviceId = device.getId();
        this.id.groupId = group.getId();
        this.addedByActorId = actorId;
        this.addedByUsername = username;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class MembershipId implements Serializable {
        @Column(name = "device_id")
        private Long deviceId;

        @Column(name = "group_id")
        private Long groupId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MembershipId other)) return false;
            return java.util.Objects.equals(deviceId, other.deviceId)
                && java.util.Objects.equals(groupId, other.groupId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(deviceId, groupId);
        }
    }
}
