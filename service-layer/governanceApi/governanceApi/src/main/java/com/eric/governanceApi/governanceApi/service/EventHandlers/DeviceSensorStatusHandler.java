package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Sensor;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.service.SensorService;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceSensorStatusHandler implements DeviceEventHandler {

    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final SensorService sensorService;

    public EventType handles() {
        return EventType.DEVICE_SENSOR_STATUS_CHANGED;
    }

    @Transactional
    public void process(DeviceEventWebhookDTO event) {

        log.info("PROCESSANDO EVENTO {} PARA DEVICE ID-> {}", this.handles().toString(), event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setOcurredAt(event.timestamp());
        
        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(event.deviceId());
        

        // Device remetente não encontrado
        if(!deviceOptional.isPresent()) {
            log.warn("Device de ID {} não encontrado.", event.deviceId());
            eventRegistry.setDevice(null);
            eventRegistry.setResultMessage("Device de ID ["+ event.deviceId() + "] não encontrado.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }   

        Device device = deviceOptional.get();
        eventRegistry.setDevice(device);
        device.setLastSeen(event.timestamp());
        
        String activeSensors = event.deviceInfo().activeSensors();
        List<String> active = (activeSensors == null || activeSensors.isBlank()) ? List.of()
                : Arrays.stream(activeSensors.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();

        Map<String, Sensor> catalog = sensorService.ensureRegistered(active);

        Map<String, Boolean> status = device.getSensorStatus();
        log.info("Device {} sensorStatus changed: {} -> {}", device.getDeviceId(), status, activeSensors);

        // declared sensors that stopped reporting go false; reported ones are added even if not declared
        status.replaceAll((code, v) -> active.contains(code));
        active.stream().filter(catalog::containsKey).forEach(code -> status.put(code, true));

        eventRegistry.setResultMessage(describe(event.details()));
        eventRegistry.setCompleted(true);
        eventRegistryRepository.save(eventRegistry);
    }

    // e.g. "Conectado: GPS · Desconectado: AHT25"
    private String describe(Map<String, Object> details) {
        if (details == null) return null;
        List<String> added = codes(details.get("added"));
        List<String> removed = codes(details.get("removed"));

        List<String> all = new ArrayList<>(added);
        all.addAll(removed);
        Map<String, Sensor> names = sensorService.findByCodes(all);

        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) parts.add("Conectado: " + join(added, names));
        if (!removed.isEmpty()) parts.add("Desconectado: " + join(removed, names));
        if (parts.isEmpty()) return null;

        String msg = String.join(" · ", parts);
        return msg.length() > 255 ? msg.substring(0, 252) + "..." : msg;
    }

    private static List<String> codes(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String join(List<String> codes, Map<String, Sensor> names) {
        return String.join(", ", codes.stream()
                .map(c -> names.containsKey(c) ? names.get(c).getName() : c)
                .toList());
    }
}