package com.eric.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/*
  DTO genérico que o MDM envia ao Agent.
  O Agent publica em: commands/<MAC>/<subtopic> com o payload fornecido.
  
  Funciona para qualquer comando:
    OTA    -> subtopic="ota",    payload={"version":4,"url":"http://..."}
    REBOOT -> subtopic="reboot", payload={"command":"reboot","delay_ms":3000}
    SLEEP  -> subtopic="sleep",  payload={"command":"sleep","duration_s":3600}
 */
public record AgentBroadcastRequest(

    @NotBlank(message = "subtopic is required")
    String subtopic,

    @NotBlank(message = "payload is required")
    String payload,

    @NotEmpty(message = "targetMacs must contain at least one MAC address")
    List<String> targetMacs

) {}