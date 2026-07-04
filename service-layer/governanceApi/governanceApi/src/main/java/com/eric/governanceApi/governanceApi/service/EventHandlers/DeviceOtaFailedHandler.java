package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceOtaFailedHandler implements DeviceEventHandler {

    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;

    @Override
    public EventType handles() { return EventType.DEVICE_UPDATE_FAILED; }

    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO DEVICE_UPDATE_FAILED PARA DEVICE ID-> {}", event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
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
        device.setLastSeen(event.timestamp());
        eventRegistry.setDevice(device);

        Map<String, Object> params = event.deviceInfo().params();
        float failedVersion = (params != null && params.containsKey("invalid_ver"))
                ? ((Number) params.get("invalid_ver")).floatValue()
                : 0f;
        String reason = (params != null && params.containsKey("reason"))
                ? params.get("reason").toString()
                : "unknown";

        log.warn("Device [{}] falhou ao atualizar para v{} — reason: {}", event.deviceId(), failedVersion, reason);

        device.setStatus(DeviceStatus.ACTIVE);

        eventRegistry.setResultMessage(String.format(
                "OTA para v%.0f falhou no device [%s] (reason: %s). Device retornou para ACTIVE.",
                failedVersion, device.getDeviceId(), reason));
        eventRegistry.setCompleted(true);
        eventRegistryRepository.save(eventRegistry);
    }
}
