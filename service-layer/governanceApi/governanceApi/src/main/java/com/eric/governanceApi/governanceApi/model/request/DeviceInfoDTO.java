package com.eric.governanceApi.governanceApi.model.request;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Atributo do DTO enviado pelo event-handler
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceInfoDTO(

    String mac,
    String firmwareVersion,
    String ssid,
    String status,
    Map<String, Object> params,
    String activeSensors
) {
    

}
