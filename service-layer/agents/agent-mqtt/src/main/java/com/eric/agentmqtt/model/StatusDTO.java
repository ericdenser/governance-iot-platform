package com.eric.agentmqtt.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusDTO(

    String device_id,

    @NotBlank(message = "mac cannot be blank")
    String mac,

    @NotBlank(message = "firmware_version cannot be blank")
    String firmware_version,

    @NotBlank(message = "ssid cannot be blank")
    String ssid,

    @NotNull(message = "status cannot be null")
    String status,

    Map<String, Object> params

) {}
