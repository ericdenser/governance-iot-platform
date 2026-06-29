package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.model.entity.DeviceGroup;

public record DeviceGroupResponseDTO(
        String groupId,
        String name,
        String description,
        String createdByActorId,
        String createdByUsername,
        Instant createdAt
) {
    public static DeviceGroupResponseDTO from(DeviceGroup g) {
        return new DeviceGroupResponseDTO(
                g.getGroupId(),
                g.getName(),
                g.getDescription(),
                g.getCreatedByActorId(),
                g.getCreatedByUsername(),
                g.getCreatedAt()
        );
    }
}
