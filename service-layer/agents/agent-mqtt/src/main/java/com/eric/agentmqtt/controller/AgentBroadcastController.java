package com.eric.agentmqtt.controller;

import com.eric.agentmqtt.model.AgentBroadcastRequest;
import com.eric.agentmqtt.service.AgentBroadcastService;
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

    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(
            @Valid @RequestBody AgentBroadcastRequest request) {

        log.info("Broadcast recebido — command: [{}] para {} devices",
                 request.command(), request.targetDevices().size());

        return ResponseEntity.ok(broadcastService.broadcast(request));
    }
}
