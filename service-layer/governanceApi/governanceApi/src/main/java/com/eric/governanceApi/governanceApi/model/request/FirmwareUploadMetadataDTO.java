package com.eric.governanceApi.governanceApi.model.request;

import java.util.List;


public record FirmwareUploadMetadataDTO ( 
    String version,
    boolean isProvisioning,
    String releaseNotes,
    List<SensorConfigDTO> sensors){
}