package com.eric.agent.model;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StatusDTO(

    @NotBlank(message = "macAddress cannot be blank")
    String mac,

    @NotBlank(message = "firm version cannot be blank")
    String firmware_version,

    @NotBlank(message = "ssid cannot be blank")
    String ssid,

    @NotNull(message = "status cannot be null")
    String status

) {
    
}
