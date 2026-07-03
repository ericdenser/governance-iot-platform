package com.eric.governanceApi.governanceApi.model.request;

import java.util.List;


public record CreateFirmwareRequestDTO (
    String firmwareName,
    String description,
    String initialVersion,
    boolean isProvisioning,
    String ownerGroupId,
    List<SensorConfigDTO> sensors){
}