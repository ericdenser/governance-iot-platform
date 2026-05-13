package com.eric.eventhandler.event_handler.model;
import com.eric.eventhandler.event_handler.enums.DeviceState;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StatusDTO(

    @NotBlank(message = "macAddress cannot be blank")
    String mac,

    @NotBlank(message = "firm version cannot be blank")
    Double firmware_version,

    @NotBlank(message = "ssid cannot be blank")
    String ssid,

    @NotNull(message = "status cannot be null")
    DeviceState status

) {
    
}
