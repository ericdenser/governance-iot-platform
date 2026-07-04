package com.eric.governanceApi.governanceApi.model.request;

import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


public record CreateFirmwareRequestDTO (
    @NotBlank @Size(max = 50)
    String firmwareName,

    @Size(max = 500)
    String description,

    @NotBlank @Size(max = 30)
    String initialVersion,

    boolean isProvisioning,

    @Nullable
    @Pattern(regexp = ValidationPatterns.UUID_REGEX, message = "ownerGroupId " + ValidationPatterns.UUID_MESSAGE)
    String ownerGroupId,

    @Valid
    List<SensorConfigDTO> sensors){
}