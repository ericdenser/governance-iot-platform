package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;

public record DeviceSummaryDTO(
    String deviceId,
    String name,
    DeviceStatus status,
    String macAddress,
    String firmwareVersion,
    Instant createdAt,
    Instant lastSeen,
    String issuedByActorId,
    String issuedByUsername
) {
    public static DeviceSummaryDTO from(Device device) {
        return new DeviceSummaryDTO(
            device.getDeviceId(),
            device.getName(),
            device.getStatus(),
            device.getMacAddress(),
            device.getFirmware() != null ? device.getFirmware().getVersion() : null,
            device.getCreatedAt(),
            device.getLastSeen(),
            device.getIssuedByActorId(),
            device.getIssuedByUsername()
        );
    }
}
