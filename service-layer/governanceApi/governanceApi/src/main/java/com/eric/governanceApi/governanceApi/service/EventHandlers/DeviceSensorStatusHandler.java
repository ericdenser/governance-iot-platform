package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceSensorStatusHandler implements DeviceEventHandler {

    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;

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
        eventRegistry.setUploadedAt(event.timestamp());
        
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
        List<String> active = (activeSensors == null || activeSensors.isBlank()) ? List.of() : Arrays.asList(activeSensors.split(","));
        
        Map<String, Boolean> currentSensorStatus = device.getSensorStatus();
        
        if (currentSensorStatus == null || currentSensorStatus.isEmpty()) {
            log.warn("Device {} sem sensorStatus inicializado. (firmware sem sensores?", device.getDeviceId());
            return;
        }

        log.info("Device {} sensorStatus changed: {} -> {}", device.getDeviceId(), currentSensorStatus.toString(), activeSensors);

        currentSensorStatus.replaceAll((sensorName, v) -> active.contains(sensorName));
        
        eventRegistry.setCompleted(true);
        eventRegistryRepository.save(eventRegistry);
    }   

}