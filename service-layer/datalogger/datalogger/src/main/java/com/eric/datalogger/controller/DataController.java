package com.eric.datalogger.controller;

import com.eric.datalogger.model.LastPositionDTO;
import com.eric.datalogger.model.TelemetryFieldsDTO;
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
    //  Ingest
    // -------------------------------------------------------------------------
    // CONTROLLER LEGADO DA ARQUITETURA USANDO HTTP INTERNO
    
    // @PostMapping("/ingest")
    // public ResponseEntity<String> ingestTelemetry(@RequestBody TelemetryDTO dto) {
    //     try {
    //         influxService.writeTelemetry(dto);
    //         return ResponseEntity.ok("Telemetry ingested");
    //     } catch (Exception e) {
    //         return ResponseEntity.internalServerError().body("Error while ingesting telemetry: " + e);
    //     }
    // }

    // -------------------------------------------------------------------------
    //  Query
    // -------------------------------------------------------------------------

    /**
     * Retorna série temporal de um device.
     * ?minutes=60 (padrão) — pode ir até 1440 (24 h).
     *
     * Exemplo de resposta:
     * [
     *   { "timestamp": "2026-06-17T10:00:00Z", "readings": { "temperature": 24.5, "humidity": 61.2 } },
     *   ...
     * ]
     */
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

    /**
     * Retorna a última posição conhecida do device (últimos 30 dias).
     * 404 se o device nunca reportou latitude/longitude.
     *
     * Exemplo de resposta:
     * { "deviceId": "abc-123", "latitude": -23.55, "longitude": -46.63, "altitude": 760.0, "recordedAt": "2026-06-17T10:00:00Z" }
     */
    @GetMapping("/telemetry/{deviceId}/last-known-position")
    public ResponseEntity<LastPositionDTO> getLastKnownPosition(@PathVariable String deviceId) {
        try {
            LastPositionDTO position = influxService.queryLastPosition(deviceId);
            if (position == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(position);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Retorna quais sensores um device já reportou (últimos 30 dias).
     * Usado pelo BFF para saber quais gráficos renderizar no dashboard.
     *
     * Exemplo de resposta:
     * { "deviceId": "abc-123", "fields": ["temperature", "humidity", "battery_mv"] }
     */
    @GetMapping("/telemetry/{deviceId}/fields")
    public ResponseEntity<TelemetryFieldsDTO> getFields(@PathVariable String deviceId) {
        try {
            TelemetryFieldsDTO fields = influxService.queryFields(deviceId);
            return ResponseEntity.ok(fields);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
