package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameSensorDTO(
    @NotBlank(message = "name is required") @Size(max = 60)
    String name
) {}
