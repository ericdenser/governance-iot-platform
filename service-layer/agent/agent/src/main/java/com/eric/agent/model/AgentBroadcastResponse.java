package com.eric.agent.model;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/*
  O Agent publica em: commands/<MAC>/ com o payload fornecido.
  
  Funciona para qualquer comando:
    OTA    -> command="ota",    payload={"version":4,"url":"..."}
    REBOOT -> command="reboot", payload={"command":"reboot"}
    SLEEP  -> command="sleep",  payload={"command":"sleep"}
 */
public record AgentBroadcastResponse(

    @NotBlank(message = "command is required")
    String command,

    @NotBlank(message = "payload is required")
    Map<String, Object> payload,

    @NotEmpty(message = "targetMac must contain at least one MAC address")
    String targetMac

) {}