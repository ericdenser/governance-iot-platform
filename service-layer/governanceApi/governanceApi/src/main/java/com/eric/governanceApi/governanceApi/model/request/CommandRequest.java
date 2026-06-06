package com.eric.governanceApi.governanceApi.model.request;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 DTO para comandos.
 
 Exemplos:
    REBOOT:     { "command": "REBOOT",     "targetDevices": ["5125GQB:..."], "params": {"delay_ms": 3000} }
    DEEP_SLEEP: { "command": "DEEP_SLEEP", "targetDevices": ["A415fQ:..."], "params": {"duration_s": 3600} }
*/
public record CommandRequest(

    @NotNull(message = "command is required")
    DeviceCommands command,

    @NotEmpty(message = "targetDevices must contain at least one device id")
    List<String> targetDevices,

    Map<String, Object> params  // nullable — REBOOT pode não ter params

) {}