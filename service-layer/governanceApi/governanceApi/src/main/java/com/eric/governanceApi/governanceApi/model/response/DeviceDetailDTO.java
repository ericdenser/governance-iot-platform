package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.service.HotStateService.LiveState;

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
    String issuedByUsername,
    Double lastLatitude,
    Double lastLongitude
) {
    public static DeviceDetailDTO from(Device device, LiveState liveState) {
        FirmwareVersion v = device.getFirmwareVersion();

        return new DeviceDetailDTO(
            device.getDeviceId(),
            device.getName(),
            device.getStatus(),
            device.getMacAddress(),
            device.getCreatedAt(),
            preferLive(liveState.lastSeen(), device.getLastSeen()),
            v != null ? FirmwareSummaryDTO.from(v.getFirmware()) : null,
            v != null ? FirmwareVersionSummaryDTO.from(v) : null,
            new HashMap<>(device.getSensorStatus()),
            device.getIssuedByActorId(),
            device.getIssuedByUsername(),
            preferLive(liveState.latitude(), device.getLastLatitude()),
            preferLive(liveState.longitude(), device.getLastLongitude())

        );
    }

     private static <T> T preferLive(T live, T fallback) {
        return live != null ? live : fallback;
    }
}
