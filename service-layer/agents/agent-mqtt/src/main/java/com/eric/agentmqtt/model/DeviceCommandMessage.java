package com.eric.agentmqtt.model;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record DeviceCommandMessage(

    @NotBlank(message = "command is required")
    String command,

    Map<String, Object> payload,

    @NotEmpty(message = "targetDev must contain at least one Device Id")
    String targetDev

) {}
