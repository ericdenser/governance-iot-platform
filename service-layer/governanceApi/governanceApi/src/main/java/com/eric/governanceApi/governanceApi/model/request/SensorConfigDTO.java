package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SensorConfigDTO(
    @NotBlank
    @Pattern(regexp = ValidationPatterns.UUID_REGEX, message = "sensorId " + ValidationPatterns.UUID_MESSAGE)
    String sensorId,
    int pin
) {}
