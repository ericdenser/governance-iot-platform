package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.List;

import com.eric.governanceApi.governanceApi.enums.TargetChip;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;

// Device still waiting for its first activation (provisioning page)
public record PendingDeviceDTO(
    String deviceId,
    String name,
    TargetChip chip,
    DeviceStatus status,
    Instant createdAt,
    String createdByUsername,
    List<String> groupNames,
    Instant tokenExpiresAt
) {}
