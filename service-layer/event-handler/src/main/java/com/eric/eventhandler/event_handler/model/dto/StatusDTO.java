package com.eric.eventhandler.event_handler.model.dto;
import java.util.Map;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
// DTO enviado pelo agent
public record StatusDTO(

    @NotBlank(message = "device_id cannot be blank")
    String device_id,

    @NotBlank(message = "macAddress cannot be blank")
    String mac,

    @JsonProperty("fw_version")
    @NotNull(message = "firm version cannot be blank")
    Double firmware_version,

    @NotBlank(message = "ssid cannot be blank")
    String ssid,

    @NotNull(message = "status cannot be null")
    DeviceState status,

    Map<String, Object> params

) {
    
}
