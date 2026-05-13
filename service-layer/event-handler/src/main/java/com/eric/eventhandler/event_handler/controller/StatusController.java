package com.eric.eventhandler.event_handler.controller;

import java.util.Map;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.eric.eventhandler.event_handler.model.StatusDTO;
import com.eric.eventhandler.event_handler.service.EventManagerService;

@Slf4j
@RestController
@RequestMapping("/events")
public class StatusController {

    EventManagerService eventService;

    public StatusController() {

    }


    @PostMapping
    public ResponseEntity<Map<String, String>> handleStatus(@Valid @RequestBody StatusDTO statusDTO){
        eventService.handleStatus(statusDTO);
    }
}
