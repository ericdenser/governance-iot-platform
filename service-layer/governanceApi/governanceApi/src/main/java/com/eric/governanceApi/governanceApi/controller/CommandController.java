package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.ApiResponse;
import com.eric.governanceApi.governanceApi.model.CommandRequest;
import com.eric.governanceApi.governanceApi.service.CommandsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandsService commandsService;

    public CommandController(CommandsService commandsService) {
        this.commandsService = commandsService;
    }

    /**
     Endpoint para comandos (REBOOT, DEEP_SLEEP, futuros).
     
     POST /api/commands
     {
        "command": "REBOOT",
        "targetMacs": ["AA:", "BB"],
        "params": { "delay_ms": 5000 }
      }
      
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendCommand(
            @Valid @RequestBody CommandRequest request,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Comando [{}] recebido para {} devices",
                 request.command(), request.targetMacs().size());

        Map<String, Object> result = commandsService.execute(request);
        return ResponseEntity.ok(ApiResponse.success(result, httpRequest.getRequestURI()));
    }
}