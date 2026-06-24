package com.eric.datalogger.model;

import java.time.Instant;
import java.util.Map;

public record TelemetryPointDTO(
    Instant timestamp,
    Map<String, Double> readings
) {}
