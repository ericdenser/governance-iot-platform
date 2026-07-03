package com.eric.governanceApi.governanceApi.model.projection;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;

public record DeviceSummaryProjection(
    String deviceId, 
    String name, 
    DeviceStatus status, 
    String macAddress,

    String firmwareId, 
    String firmwareName, 
    String firmwareVersionId, 
    String version,
    
    Instant createdAt, 
    Instant lastSeen,
    String issuedByActorId, 
    String issuedByUsername
) {}