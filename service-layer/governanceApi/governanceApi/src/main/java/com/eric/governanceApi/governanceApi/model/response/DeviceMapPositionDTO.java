package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

public record DeviceMapPositionDTO(
    String deviceId,
    String name,
    Double latitude,
    Double longitude,
    Instant lastSeen,
    String status
) {}
