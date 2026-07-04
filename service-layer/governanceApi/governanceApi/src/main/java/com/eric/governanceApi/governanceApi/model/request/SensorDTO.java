package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SensorDTO (
    @NotBlank(message = "name is required") @Size(max = 20)
    String name
){
}
