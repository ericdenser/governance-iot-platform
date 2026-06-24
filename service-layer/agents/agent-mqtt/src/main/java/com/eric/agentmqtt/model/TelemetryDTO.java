package com.eric.agentmqtt.model;

import java.time.Instant;
import java.util.Map;

public record TelemetryDTO(
    String deviceId,
    Map<String, Float> readings,
    Instant deviceTimestamp
) {}
