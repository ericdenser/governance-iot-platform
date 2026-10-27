package com.eric.datalogger.controller;

import com.eric.datalogger.model.TelemetryPointDTO;
import com.eric.datalogger.service.InfluxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/datalogger")
public class DataController {

    private final InfluxService influxService;

    public DataController(InfluxService influxService) {
        this.influxService = influxService;
    }

    // -------------------------------------------------------------------------
    //  Query
    // -------------------------------------------------------------------------

    /**
     * Retorna série temporal agregada de um device.
     *
     * Parâmetros:
     *   minutes    — janela de tempo (1–1440, padrão 60)
     *   resolution — tamanho de cada bucket de agregação (padrão "1m")
     *                Valores aceitos: 5s, 15s, 30s, 1m, 5m, 15m, 30m, 1h
     *
     * Referência de pontos retornados (minutes / resolution):
     *   60m  / 1m  →  60 pontos   (dashboard ao vivo)
     *   360m / 5m  →  72 pontos   (últimas 6h)
     *   1440m/ 15m →  96 pontos   (24h)
     */
    @GetMapping("/telemetry/{deviceId}")
    public ResponseEntity<List<TelemetryPointDTO>> getTelemetry(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "60") int minutes,
            @RequestParam(defaultValue = "1m") String resolution) {

        if (minutes < 1 || minutes > 1440) {
            return ResponseEntity.badRequest().build();
        }

        try {
            List<TelemetryPointDTO> points = influxService.queryTelemetry(deviceId, minutes, resolution);
            return ResponseEntity.ok(points);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
