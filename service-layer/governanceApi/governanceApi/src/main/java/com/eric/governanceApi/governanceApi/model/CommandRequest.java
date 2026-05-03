package com.eric.governanceApi.governanceApi.model;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 DTO para comandos.
 
 Exemplos:
    REBOOT:     { "command": "REBOOT",     "targetMacs": ["AA:BB:..."], "params": {"delay_ms": 3000} }
    DEEP_SLEEP: { "command": "DEEP_SLEEP", "targetMacs": ["AA:BB:..."], "params": {"duration_s": 3600} }
*/
public record CommandRequest(

    @NotNull(message = "command is required")
    DeviceCommands command,

    @NotEmpty(message = "targetMacs must contain at least one MAC address")
    List<String> targetMacs,

    Map<String, Object> params  // nullable — REBOOT pode não ter params

) {}