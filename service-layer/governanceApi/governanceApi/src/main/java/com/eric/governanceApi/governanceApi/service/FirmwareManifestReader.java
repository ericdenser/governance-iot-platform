package com.eric.governanceApi.governanceApi.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eric.governanceApi.governanceApi.model.request.ValidationPatterns;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

// Reads the GOV_SENSOR_INFO strings (sensors, pins, metrics) compiled into the firmware .bin
@Slf4j
public final class FirmwareManifestReader {

    public record SensorInfo(String code, Map<String, Integer> pins, List<String> metrics) {}

    private static final byte[] MARKER = "GOVSENSOR:".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_JSON_BYTES = 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FirmwareManifestReader() {}

    public static List<SensorInfo> read(byte[] bin) {
        List<SensorInfo> out = new ArrayList<>();
        if (bin == null) return out;

        for (int i = indexOf(bin, 0); i >= 0; i = indexOf(bin, i + 1)) {
            int start = i + MARKER.length;
            int end = start;
            while (end < bin.length && bin[end] != 0 && end - start < MAX_JSON_BYTES) end++;
            String json = new String(bin, start, end - start, StandardCharsets.UTF_8);

            SensorInfo info = parse(json);
            if (info != null && out.stream().noneMatch(s -> s.code().equals(info.code()))) out.add(info);
        }
        return out;
    }

    private static SensorInfo parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String code = node.path("code").asText("");
            if (!code.matches(ValidationPatterns.SENSOR_CODE_REGEX)) {
                log.warn("Manifesto de sensor com code invalido ignorado: {}", json);
                return null;
            }

            Map<String, Integer> pins = new LinkedHashMap<>();
            node.path("pins").properties().forEach(e -> {
                if (e.getValue().canConvertToInt()) pins.put(e.getKey(), e.getValue().asInt());
            });

            List<String> metrics = new ArrayList<>();
            node.path("metrics").forEach(m -> metrics.add(m.asText()));

            return new SensorInfo(code, pins, metrics);
        } catch (Exception e) {
            log.warn("Manifesto de sensor ilegivel ignorado: {}", json);
            return null;
        }
    }

    private static int indexOf(byte[] bin, int from) {
        outer:
        for (int i = from; i <= bin.length - MARKER.length; i++) {
            for (int j = 0; j < MARKER.length; j++) {
                if (bin[i + j] != MARKER[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
