package com.eric.agent.model;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusDTO(

    @NotBlank(message = "Device_Id cannot be blank")
    String device_id,

    @NotBlank(message = "macAddress cannot be blank")
    String mac,

    @JsonProperty("fw_version")
    String firmwareVersion,

    @NotBlank(message = "ssid cannot be blank")
    String ssid,

    @NotNull(message = "status cannot be null")
    String status,
    
    Map<String, Object> params

) {
    
}
