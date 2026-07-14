package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

public record DeviceMapPositionDTO(
    String deviceId,
    Double latitude,
    Double longitude,
    Instant lastSeen
) {}
