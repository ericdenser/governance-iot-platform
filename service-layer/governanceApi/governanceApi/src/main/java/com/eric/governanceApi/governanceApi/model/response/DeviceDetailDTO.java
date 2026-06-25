package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.Map;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;

public record DeviceDetailDTO(
    String deviceId,
    String name,
    DeviceStatus status,
    String macAddress,
    Instant createdAt,
    Instant lastSeen,
    FirmwareSummaryDTO firmware,
    Map<String, Boolean> sensorStatus
) {
    public static DeviceDetailDTO from(Device device) {
        return new DeviceDetailDTO(
            device.getDeviceId(),
            device.getName(),
            device.getStatus(),
            device.getMacAddress(),
            device.getCreatedAt(),
            device.getLastSeen(),
            FirmwareSummaryDTO.from(device.getFirmware()),
            device.getSensorStatus()
        );
    }
}
