package com.eric.datalogger.service;

import com.eric.datalogger.model.TelemetryDTO;
import com.eric.datalogger.model.TelemetryPointDTO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class InfluxService {

    private static final Logger log = LoggerFactory.getLogger(InfluxService.class);

    // Colunas de metadata que o pivot injeta — não são leituras de sensor
    private static final Set<String> IGNORED_COLUMNS = Set.of(
        "_start", "_stop", "_measurement", "device_id", "result", "table"
    );

    @Value("${influxdb.url}")
    private String url;

    @Value("${influxdb.token}")
    private String token;

    @Value("${influxdb.org}")
    private String org;

    @Value("${influxdb.bucket}")
    private String bucket;

    private InfluxDBClient influxDBClient;
    private WriteApiBlocking writeApi;
    private QueryApi queryApi;

    @PostConstruct
    public void init() {
        influxDBClient = InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
        writeApi = influxDBClient.getWriteApiBlocking();
        queryApi  = influxDBClient.getQueryApi();
    }

    @PreDestroy
    public void close() {
        if (influxDBClient != null) influxDBClient.close();
    }

    // -------------------------------------------------------------------------
    //  Write
    // -------------------------------------------------------------------------

 
    public void writeTelemetryBatch(List<TelemetryDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) return;

        Instant now = Instant.now();
        List<Point> points = new ArrayList<>(dtos.size());
        for (TelemetryDTO dto : dtos) {
            if (dto.readings() == null || dto.readings().isEmpty()) continue;

            Point point = Point.measurement("telemetry")
                .addTag("device_id", dto.deviceId())
                .time(sanitizeTimestamp(dto.deviceTimestamp(), now), WritePrecision.NS);

            dto.readings().forEach((key, value) -> point.addField(key, value));
            points.add(point);
        }
        if (points.isEmpty()) return;

        try {
            writeApi.writePoints(points);
        } catch (InfluxException e) {
            // 400/422 = ponto invalido (ex: conflito de tipo de field). O resto
            // (0 = sem conexao, 5xx, 401/403) e o Influx indisponivel.
            if (e.status() == 400 || e.status() == 422) throw e;
            throw new InfluxUnavailableException("Influx indisponivel (status " + e.status() + "): " + e.getMessage(), e);
        }
    }

    // ESP sem SNTP manda deviceTimestamp = uptime desde epoch 0 (pontos vão pra
    // 1970, quebram Data Explorer e "last seen"). Aceita só ts dentro de janela
    // sã; fora dela, usa o tempo do datalogger.
    private static final Instant MIN_VALID_TS = Instant.parse("2020-01-01T00:00:00Z");

    private static Instant sanitizeTimestamp(Instant deviceTs, Instant now) {
        if (deviceTs == null) return now;
        if (deviceTs.isBefore(MIN_VALID_TS)) return now;
        if (deviceTs.isAfter(now.plusSeconds(3600))) return now;
        return deviceTs;
    }

    // -------------------------------------------------------------------------
    //  Query — série temporal
    // -------------------------------------------------------------------------

    private static final Set<String> VALID_RESOLUTIONS = Set.of(
        "5s", "15s", "30s", "1m", "5m", "15m", "30m", "1h"
    );

    public List<TelemetryPointDTO> queryTelemetry(String deviceId, int minutes, String resolution) {
        validateDeviceId(deviceId);
        if (!VALID_RESOLUTIONS.contains(resolution)) {
            throw new IllegalArgumentException("Invalid resolution: " + resolution);
        }

        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: -%dm)
              |> filter(fn: (r) => r["_measurement"] == "telemetry")
              |> filter(fn: (r) => r["device_id"] == "%s")
              |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
              |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
            """, bucket, minutes, deviceId, resolution);

        List<FluxTable> tables = queryApi.query(flux, org);
        List<TelemetryPointDTO> result = new ArrayList<>();

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Instant time = record.getTime();
                Map<String, Double> readings = new LinkedHashMap<>();

                record.getValues().forEach((col, val) -> {
                    if (!IGNORED_COLUMNS.contains(col) && val instanceof Number num) {
                        readings.put(col, num.doubleValue());
                    }
                });

                if (!readings.isEmpty()) {
                    result.add(new TelemetryPointDTO(time, readings));
                }
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------

    private void validateDeviceId(String deviceId) {
        if (deviceId == null || !deviceId.matches("[0-9a-fA-F\\-]{8,36}")) {
            throw new IllegalArgumentException("Invalid device ID: " + deviceId);
        }
    }


    // ping nao usa token: a busca do bucket confirma tambem token e bucket
    public boolean isUp() {
        try {
            return Boolean.TRUE.equals(influxDBClient.ping())
                && influxDBClient.getBucketsApi().findBucketByName(bucket) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
