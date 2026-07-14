package com.eric.datalogger.service;

import com.eric.datalogger.model.LastPositionDTO;
import com.eric.datalogger.model.TelemetryDTO;
import com.eric.datalogger.model.TelemetryFieldsDTO;
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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public void writeTelemetry(TelemetryDTO dto) {
        if (dto.readings() == null || dto.readings().isEmpty()) return;

        try {
            Instant ts = dto.deviceTimestamp() != null ? dto.deviceTimestamp() : Instant.now();
            Point point = Point.measurement("telemetry")
                .addTag("device_id", dto.deviceId())
                .time(ts, WritePrecision.NS);

            dto.readings().forEach((key, value) -> point.addField(key, value));

            writeApi.writePoint(point);
        } catch (InfluxException e) {
            throw new RuntimeException(e);
        }
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
    //  Query — descoberta de campos
    // -------------------------------------------------------------------------

    /**
     * Retorna quais fields (sensores) um device já reportou nos últimos 30 dias.
     * Usado pelo BFF para saber quais gráficos renderizar no dashboard.
     */
    public TelemetryFieldsDTO queryFields(String deviceId) {
        validateDeviceId(deviceId);

        String flux = String.format("""
            import "influxdata/influxdb/schema"
            schema.fieldKeys(
              bucket: "%s",
              predicate: (r) => r["_measurement"] == "telemetry" and r["device_id"] == "%s",
              start: -30d
            )
            """, bucket, deviceId);

        List<FluxTable> tables = queryApi.query(flux, org);
        List<String> fields = new ArrayList<>();

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Object val = record.getValue();
                if (val != null) fields.add(val.toString());
            }
        }

        return new TelemetryFieldsDTO(deviceId, fields);
    }

    // -------------------------------------------------------------------------
    //  Query — última posição conhecida
    // -------------------------------------------------------------------------

    /**
     * Retorna a última leitura de latitude, longitude e altitude de um device.
     * Retorna null se o device nunca reportou posição nos últimos 30 dias.
     */
    public LastPositionDTO queryLastPosition(String deviceId) {
        validateDeviceId(deviceId);

        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: -30d)
              |> filter(fn: (r) => r["_measurement"] == "telemetry")
              |> filter(fn: (r) => r["device_id"] == "%s")
              |> filter(fn: (r) => r["_field"] == "latitude" or r["_field"] == "longitude" or r["_field"] == "altitude")
              |> last()
            """, bucket, deviceId);

        List<FluxTable> tables = queryApi.query(flux, org);

        Double latitude   = null;
        Double longitude  = null;
        Double altitude   = null;
        Instant recordedAt = null;

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                String field = record.getField();
                Object value = record.getValue();
                if (!(value instanceof Number num)) continue;

                switch (field) {
                    case "latitude"  -> { latitude  = num.doubleValue(); recordedAt = record.getTime(); }
                    case "longitude" -> longitude  = num.doubleValue();
                    case "altitude"  -> altitude   = num.doubleValue();
                }
            }
        }

        if (latitude == null || longitude == null) return null;

        return new LastPositionDTO(deviceId, latitude, longitude, altitude, recordedAt);
    }

    // -------------------------------------------------------------------------

    private void validateDeviceId(String deviceId) {
        if (deviceId == null || !deviceId.matches("[0-9a-fA-F\\-]{8,36}")) {
            throw new IllegalArgumentException("Invalid device ID: " + deviceId);
        }
    }
}
