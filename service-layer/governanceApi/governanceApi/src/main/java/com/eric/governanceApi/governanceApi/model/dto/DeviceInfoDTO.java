package com.eric.governanceApi.governanceApi.model.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// DTO enviado pelo event-handler da info do device que disparou o evento
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceInfoDTO(

    String mac,
    @JsonProperty("fw_version") float firmware_version,
    String ssid,
    String status,
    Map<String, Object> params
) {
    

}
