package com.eric.governanceApi.governanceApi.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Sensor;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.request.DeviceInfoDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;
import com.eric.governanceApi.governanceApi.service.SensorService;
import com.eric.governanceApi.governanceApi.service.EventHandlers.DeviceSensorStatusHandler;

class SensorCatalogTest {

    private static Sensor sensor(String code, String name) {
        Sensor s = new Sensor();
        s.setCode(code);
        s.setName(name);
        return s;
    }

    @Test
    void unknownCodesAreRegisteredAndInvalidOnesSkipped() {
        SensorRepository repo = mock(SensorRepository.class);
        when(repo.findByCodeIn(anyCollection())).thenReturn(List.of(sensor("bmp280", "Pressao")));
        when(repo.save(any(Sensor.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Sensor> out = new SensorService(repo).ensureRegistered(List.of("bmp280", "gps", "Bad Code"));

        assertEquals("Pressao", out.get("bmp280").getName());
        assertEquals("gps", out.get("gps").getName());
        assertFalse(out.containsKey("Bad Code"));
        verify(repo, times(1)).save(any(Sensor.class));
    }

    @Test
    void renameKeepsCode() {
        SensorRepository repo = mock(SensorRepository.class);
        Sensor s = sensor("aht25", "aht25");
        when(repo.findBySensorId("id-1")).thenReturn(Optional.of(s));

        new SensorService(repo).rename("id-1", "  Umidade AHT25 ");

        assertEquals("Umidade AHT25", s.getName());
        assertEquals("aht25", s.getCode());
    }

    @Test
    void handlerUpdatesStatusByCodeAndDescribesChange() {
        Device device = new Device();
        Map<String, Boolean> status = new HashMap<>();
        status.put("bmp280", true);
        status.put("aht25", true);
        device.setSensorStatus(status);

        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.findByDeviceId("dev-1")).thenReturn(Optional.of(device));
        EventRegistryRepository events = mock(EventRegistryRepository.class);

        SensorService sensors = mock(SensorService.class);
        Map<String, Sensor> catalog = Map.of(
            "bmp280", sensor("bmp280", "BMP280"),
            "gps", sensor("gps", "GPS"),
            "aht25", sensor("aht25", "AHT25"));
        when(sensors.ensureRegistered(anyCollection())).thenReturn(catalog);
        when(sensors.findByCodes(anyCollection())).thenReturn(catalog);

        DeviceInfoDTO info = new DeviceInfoDTO("mac", "1.0", "ssid", "OPERATIONAL", null, "bmp280,gps");
        DeviceEventWebhookDTO event = new DeviceEventWebhookDTO("DEVICE_SENSOR_STATUS_CHANGED", "dev-1",
            "OPERATIONAL", "OPERATIONAL", info, Instant.now(),
            Map.of("added", List.of("gps"), "removed", List.of("aht25")));

        new DeviceSensorStatusHandler(devices, events, sensors).process(event);

        assertTrue(status.get("bmp280"));
        assertTrue(status.get("gps"));
        assertFalse(status.get("aht25"));

        ArgumentCaptor<EventRegistry> saved = ArgumentCaptor.forClass(EventRegistry.class);
        verify(events).save(saved.capture());
        assertEquals("Conectado: GPS · Desconectado: AHT25", saved.getValue().getResultMessage());
    }
}
