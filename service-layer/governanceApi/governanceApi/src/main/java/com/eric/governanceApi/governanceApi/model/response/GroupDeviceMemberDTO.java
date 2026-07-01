package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroupMembership;

public record GroupDeviceMemberDTO(
        String deviceId,
        String name,
        DeviceStatus status,
        String addedByUsername,
        String addedByActorId,
        Instant addedAt
) {
    public static GroupDeviceMemberDTO from(DeviceGroupMembership m) {
        return new GroupDeviceMemberDTO(
                m.getDevice().getDeviceId(),
                m.getDevice().getName(),
                m.getDevice().getStatus(),
                m.getAddedByUsername(),
                m.getAddedByActorId(),
                m.getAddedAt()
        );
    }
}
