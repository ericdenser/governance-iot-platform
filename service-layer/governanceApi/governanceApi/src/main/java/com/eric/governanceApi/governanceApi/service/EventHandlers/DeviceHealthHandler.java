package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.status.DeviceHealth;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Base dos eventos DEVICE_HEALTH_WARNING / DEVICE_HEALTH_RECOVERED.
// O event-handler manda os motivos ativos em details.reasons; vazio = OK.
@Slf4j
@RequiredArgsConstructor
public abstract class DeviceHealthHandler implements DeviceEventHandler {

    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;

    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO {} PARA DEVICE ID-> {}", this.handles().toString(), event.deviceId());

        Map<String, Object> details = event.details() != null ? event.details() : Map.of();
        String reasons = details.get("reasons") != null ? String.valueOf(details.get("reasons")) : "";
        DeviceHealth newHealth = reasons.isBlank() ? DeviceHealth.OK : DeviceHealth.WARNING;

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setNewStatus(newHealth.name());
        eventRegistry.setOcurredAt(event.timestamp());

        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(event.deviceId());
        if (deviceOptional.isEmpty()) {
            log.warn("Device de ID {} não encontrado.", event.deviceId());
            eventRegistry.setDevice(null);
            eventRegistry.setResultMessage("Device de ID [" + event.deviceId() + "] não encontrado.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }

        Device device = deviceOptional.get();
        eventRegistry.setDevice(device);
        eventRegistry.setPreviousStatus(device.getHealth().name());

        device.setHealth(newHealth);
        device.setHealthReason(reasons.isBlank() ? null : reasons);

        eventRegistry.setCompleted(true);
        eventRegistry.setResultMessage(describe(reasons, details));
        eventRegistryRepository.save(eventRegistry);
    }

    private String describe(String reasons, Map<String, Object> details) {
        if (reasons.isBlank()) return "Bateria e temperatura dentro do limite.";

        // valor pode faltar se o motivo veio de uma leitura anterior
        List<String> parts = new ArrayList<>();
        if (reasons.contains("LOW_BATTERY")) parts.add("bateria baixa" + reading(details.get("batteryMv"), " mV"));
        if (reasons.contains("HIGH_TEMP"))   parts.add("temperatura alta" + reading(details.get("tempC"), " °C"));
        return "Alerta: " + String.join(", ", parts) + ".";
    }

    private String reading(Object value, String unit) {
        return value != null ? " (" + value + unit + ")" : "";
    }
}
