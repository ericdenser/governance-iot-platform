package com.eric.governanceApi.governanceApi.controller;

import java.util.List;

import com.eric.governanceApi.governanceApi.model.request.CommandRequest;
import com.eric.governanceApi.governanceApi.model.response.ApiResponse;
import com.eric.governanceApi.governanceApi.model.response.CommandBatchResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.service.CommandsService;

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

    public CommandController(CommandsService commandsService) {
        this.commandsService = commandsService;
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
    public ResponseEntity<ApiResponse<Page<CommandBatchResponseDTO>>> listAll(
        @PageableDefault(size = 50) Pageable pageable,
        HttpServletRequest request) {

            return ResponseEntity.ok(ApiResponse.success(commandsService.listBatches(pageable), request.getRequestURI()));
    }

    // Records por device de um batch 
    @GetMapping("/{batchId}")
    public ResponseEntity<ApiResponse<List<CommandRecordResponseDTO>>> getBatchRecords(
        @PathVariable String batchId,
        HttpServletRequest request) {

            return ResponseEntity.ok(ApiResponse.success(commandsService.getBatchRecords(batchId), request.getRequestURI()));
    }

}