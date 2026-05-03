package com.eric.agent.controller;

import com.eric.agent.model.AgentBroadcastRequest;
import com.eric.agent.service.AgentBroadcastService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentBroadcastController {

    private final AgentBroadcastService broadcastService;

    public AgentBroadcastController(AgentBroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    /*
    MDM chama aqui para qualquer tipo de comando.
     ex:

     POST /agent/broadcast
     {
        "subtopic": "reboot",
        "payload": "{\"command\":\"reboot\",\"delay_ms\":3000}",
        "targetMacs": ["AA"]
    }
     */
    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(
            @Valid @RequestBody AgentBroadcastRequest request) {

        log.info("Broadcast recebido — subtopic: [{}] para {} devices",
                 request.subtopic(), request.targetMacs().size());

        Map<String, Object> result = broadcastService.broadcast(request);
        return ResponseEntity.ok(result);
    }
}