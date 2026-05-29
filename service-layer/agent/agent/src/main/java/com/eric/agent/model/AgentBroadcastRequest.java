package com.eric.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/*
  DTO genérico que o MDM envia ao Agent.
  O Agent publica em: commands/<MAC>/ com o payload fornecido.
  
  Funciona para qualquer comando:
    OTA    -> command="ota",    payload={"version":4,"url":"..."}
    REBOOT -> command="reboot", payload={"command":"reboot"}
    SLEEP  -> command="sleep",  payload={"command":"sleep"}
 */
public record AgentBroadcastRequest(

    @NotBlank(message = "command is required")
    String command,

    @NotNull(message = "payload is required")
    Map<String, Object> payload,

    @NotEmpty(message = "targetMacs must contain at least one MAC address")
    List<String> targetMacs

) {}