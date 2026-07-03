package com.eric.governanceApi.governanceApi.model.request;

import java.util.List;

public record UploadVersionRequestDTO(
    String version,
    String releaseNotes,
    List<SensorConfigDTO> sensors
) {
    
}
