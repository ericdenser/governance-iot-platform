package com.eric.eventhandler.event_handler.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.eric.eventhandler.event_handler.model.dto.StatusDTO;
import com.eric.eventhandler.event_handler.service.EventManagerService;

@Slf4j
@RestController
@RequestMapping("/events")
public class StatusController {

    private final EventManagerService eventManagerService;

    public StatusController(EventManagerService eventManagerService) {
        this.eventManagerService = eventManagerService;
    }


    @PostMapping("/ingest")
    public ResponseEntity<String> handleStatus(@Valid @RequestBody StatusDTO statusDTO){
        log.info("[STATUS CONTROLLER] Recebeu StatusDTO: {}", statusDTO);
        eventManagerService.handleStatus(statusDTO);

        return ResponseEntity.noContent().build();
    }
}
