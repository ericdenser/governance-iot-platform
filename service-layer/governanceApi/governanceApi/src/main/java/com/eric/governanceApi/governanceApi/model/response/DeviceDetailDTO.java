package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;

public record DeviceDetailDTO(
    String deviceId,
    String name,
    DeviceStatus status,
    String macAddress,
    Instant createdAt,
    Instant lastSeen,
    FirmwareSummaryDTO firmware,
    FirmwareVersionSummaryDTO firmwareVersion,
    Map<String, Boolean> sensorStatus,
    String issuedByActorId,
    String issuedByUsername
) {
    public static DeviceDetailDTO from(Device device) {
        FirmwareVersion v = device.getFirmwareVersion();

        return new DeviceDetailDTO(
            device.getDeviceId(),
            device.getName(),
            device.getStatus(),
            device.getMacAddress(),
            device.getCreatedAt(),
            device.getLastSeen(),
            v != null ? FirmwareSummaryDTO.from(v.getFirmware()) : null,
            v != null ? FirmwareVersionSummaryDTO.from(v) : null,
            new HashMap<>(device.getSensorStatus()),
            device.getIssuedByActorId(),
            device.getIssuedByUsername()
        );
    }
}
