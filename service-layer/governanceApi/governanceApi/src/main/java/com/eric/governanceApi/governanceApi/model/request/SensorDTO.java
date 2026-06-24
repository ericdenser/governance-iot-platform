package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotNull;

public record SensorDTO (
    @NotNull(message = "name is required")
    String name
){
}
