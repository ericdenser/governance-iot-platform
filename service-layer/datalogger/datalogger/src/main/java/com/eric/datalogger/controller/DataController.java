package com.eric.datalogger.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.datalogger.model.DataDTO;
import com.eric.datalogger.service.InfluxService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/datalogger")
public class DataController {

    private InfluxService influxService;

    public DataController(InfluxService influxService) {
        this.influxService = influxService;
    }

    @PostMapping("/savedata")
    public ResponseEntity<String> saveData(@RequestBody @Valid DataDTO dto) {
        System.out.println("Received data on controller");
        try{
            influxService.writePoint(dto);
            return ResponseEntity.ok("Data registered");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error while writing point: " + e);
        }
        
    }


}