package com.eric.governanceApi.governanceApi.model.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UploadVersionRequestDTO(
    @NotBlank @Size(max = 30)
    String version,

    @Size(max = 1000)
    String releaseNotes,

    @Valid
    List<SensorConfigDTO> sensors
) {}
