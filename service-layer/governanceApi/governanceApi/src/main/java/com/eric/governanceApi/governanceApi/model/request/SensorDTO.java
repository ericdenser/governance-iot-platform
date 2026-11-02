package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SensorDTO (
    @NotBlank(message = "code is required")
    @Pattern(regexp = ValidationPatterns.SENSOR_CODE_REGEX, message = "code " + ValidationPatterns.SENSOR_CODE_MESSAGE)
    String code,
    // optional, defaults to the code
    @Size(max = 60)
    String name
){
}
