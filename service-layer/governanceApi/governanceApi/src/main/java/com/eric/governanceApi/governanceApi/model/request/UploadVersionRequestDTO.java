package com.eric.governanceApi.governanceApi.model.request;

import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UploadVersionRequestDTO(
    @NotBlank @Size(max = 30)
    String version,

    @Size(max = 1000)
    String releaseNotes,

    boolean deepSleepEnabled,

    @Nullable @Min(30)
    Integer deepSleepIntervalS,

    @Valid
    List<SensorConfigDTO> sensors
) {}
