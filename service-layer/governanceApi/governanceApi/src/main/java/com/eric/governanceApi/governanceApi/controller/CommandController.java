package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.request.CommandRequest;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.service.CommandsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/commands")
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
    public ResponseEntity<ApiResponse<CommandResultResponseDTO>> sendCommand(
            @Valid @RequestBody CommandRequest request,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Comando [{}] recebido para {} devices",
                 request.command(), request.targetDevices().size());

        CommandResultResponseDTO result = commandsService.execute(request);
        return ResponseEntity.ok(ApiResponse.success(result, httpRequest.getRequestURI()));
    }
}