package com.eric.governanceApi.governanceApi.model.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
    @NotBlank @Size(max = 30)
    String deviceName,

    @Nullable
    @Pattern(regexp = ValidationPatterns.UUID_REGEX, message = "groupId " + ValidationPatterns.UUID_MESSAGE)
    String groupId
) {}
