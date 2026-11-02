package com.eric.governanceApi.governanceApi.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.service.FirmwareManifestReader;
import com.eric.governanceApi.governanceApi.service.FirmwareManifestReader.SensorInfo;

class FirmwareManifestReaderTest {

    private static byte[] bin(String... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String c : chunks) {
            out.writeBytes(new byte[] { 0x01, (byte) 0xFF, 0x00 });
            out.writeBytes(c.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }
        return out.toByteArray();
    }

    @Test
    void readsSensorsPinsAndMetrics() {
        List<SensorInfo> s = FirmwareManifestReader.read(bin(
            "GOVSENSOR:{\"code\":\"bmp280\",\"pins\":{\"SDA\":8,\"SCL\":9},\"metrics\":[\"temperature\",\"pressure\"]}",
            "other string",
            "GOVSENSOR:{\"code\":\"chip_temp\",\"pins\":{},\"metrics\":[\"temp_c\"]}"));

        assertEquals(2, s.size());
        assertEquals("bmp280", s.get(0).code());
        assertEquals(Map.of("SDA", 8, "SCL", 9), s.get(0).pins());
        assertEquals(List.of("SDA", "SCL"), List.copyOf(s.get(0).pins().keySet()));
        assertEquals(List.of("temperature", "pressure"), s.get(0).metrics());
        assertTrue(s.get(1).pins().isEmpty());
    }

    @Test
    void skipsInvalidBrokenAndDuplicateEntries() {
        List<SensorInfo> s = FirmwareManifestReader.read(bin(
            "GOVSENSOR:{\"code\":\"Bad Code\",\"pins\":{},\"metrics\":[]}",
            "GOVSENSOR:{not json",
            "GOVSENSOR:{\"code\":\"gps\",\"pins\":{\"TX\":18},\"metrics\":[]}",
            "GOVSENSOR:{\"code\":\"gps\",\"pins\":{\"TX\":1},\"metrics\":[]}"));

        assertEquals(1, s.size());
        assertEquals(18, s.get(0).pins().get("TX"));
    }

    @Test
    void binaryWithoutManifestGivesEmptyList() {
        assertTrue(FirmwareManifestReader.read(bin("nothing here")).isEmpty());
        assertTrue(FirmwareManifestReader.read(null).isEmpty());
    }

    @Test
    void pinMapRoundTrip() {
        FirmwareSensorConfig cfg = new FirmwareSensorConfig();
        cfg.setPinMap(new java.util.LinkedHashMap<>(Map.of("TX", 18)));
        assertEquals("TX=18", cfg.getPins());
        assertEquals(Map.of("TX", 18), cfg.getPinMap());
    }

    // Real build of the operational firmware, when it exists on this machine
    @Test
    void readsTheOperationalFirmwareBuild() throws Exception {
        Path bin = Path.of("../../../perceptive-layer/mqtt_protobuff/FIRM_OPERATIONAL_MQTT_PROTOBUFF/build/GOVERNANCE_FIRMWARE.bin");
        assumeTrue(Files.exists(bin));

        List<SensorInfo> s = FirmwareManifestReader.read(Files.readAllBytes(bin));
        assertEquals(List.of("bmp280", "mq135", "gps", "ds3231", "battery_adc", "chip_temp"),
                     s.stream().map(SensorInfo::code).toList());
        SensorInfo gps = s.get(2);
        assertEquals(Map.of("TX", 18, "RX", 17, "POWER", 5), gps.pins());
    }
}
