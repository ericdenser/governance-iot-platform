package com.eric.datalogger.model;

import java.time.Instant;

public record LastPositionDTO(
    String deviceId,
    Double latitude,
    Double longitude,
    Double altitude,
    Instant recordedAt
) {}
