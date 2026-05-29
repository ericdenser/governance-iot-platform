package com.eric.eventhandler.event_handler.model.dto;
import com.eric.eventhandler.event_handler.enums.DeviceState;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// DTO enviado pelo agent
public record StatusDTO(

    @NotBlank(message = "macAddress cannot be blank")
    String mac,

    @NotNull(message = "firm version cannot be blank")
    Double firmware_version,

    @NotBlank(message = "ssid cannot be blank")
    String ssid,

    @NotNull(message = "status cannot be null")
    DeviceState status

) {
    
}
