package com.eric.governanceApi.governanceApi.controller;

import com.eric.governanceApi.governanceApi.model.request.CommandRequest;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.service.CommandsService;
import com.eric.governanceApi.governanceApi.service.DeviceService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/commands")
public class CommandController {

    private final CommandsService commandsService;
    private final DeviceService deviceService;

    public CommandController(CommandsService commandsService, DeviceService deviceService) {
        this.commandsService = commandsService;
        this.deviceService = deviceService;
    }

    /**
     Endpoint para comandos (REBOOT, DEEP_SLEEP, futuros).
     
     POST /commands
     {
        "command": "REBOOT",
        "targetMacs": ["AA:", "BB"],
        "params": { "delay_ms": 5000 }
      }
      
     */
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<CommandResultResponseDTO>> sendCommand(
            @Valid @RequestBody CommandRequest request,
            HttpServletRequest httpRequest) throws Exception {

        log.info("Comando [{}] recebido para {} devices",
                 request.command(), request.targetDevices().size());

        CommandResultResponseDTO result = commandsService.execute(request);
        return ResponseEntity.ok(ApiResponse.success(result, httpRequest.getRequestURI()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<CommandRecordResponseDTO>>> listAll(
        @PageableDefault(size = 50) Pageable pageable,
        HttpServletRequest request) {

            return ResponseEntity.ok(ApiResponse.success(deviceService.listAllCommands(pageable), request.getRequestURI()));
    }
    
}