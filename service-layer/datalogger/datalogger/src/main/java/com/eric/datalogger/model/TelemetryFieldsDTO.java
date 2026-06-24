package com.eric.datalogger.model;

import java.util.List;

public record TelemetryFieldsDTO(
    String deviceId,
    List<String> fields
) {}
