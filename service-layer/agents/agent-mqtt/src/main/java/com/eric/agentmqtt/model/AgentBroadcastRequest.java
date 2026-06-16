package com.eric.agentmqtt.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record AgentBroadcastRequest(

    @NotBlank(message = "command is required")
    String command,

    @NotNull(message = "payload is required")
    Map<String, Object> payload,

    @NotEmpty(message = "targetDevices must contain at least one Device ID")
    List<String> targetDevices

) {}
