package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceCriticalBatteryHandler implements DeviceEventHandler{
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;

    @Override
    public EventType handles() { return EventType.DEVICE_CRITICAL_BATTERY; }

    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO {} PARA DEVICE ID-> {}", this.handles().toString(), event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setOcurredAt(event.timestamp());

        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(event.deviceId());

        // Se não existe este device
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

        device.setStatus(DeviceStatus.CRITICAL_BATTERY);
        eventRegistry.setCompleted(true);
        eventRegistry.setResultMessage("Device com bateria crítica.");
        eventRegistryRepository.save(eventRegistry);
        return;
    }


    
}
