package com.eric.governanceApi.governanceApi.model.response;

import java.util.List;
import java.util.Map;

public record FirmwareSensorConfigResponseDTO ( 
    String sensorCode,
    String sensorName,
    Map<String, Integer> pins,
    List<String> metrics
) {
    
}
