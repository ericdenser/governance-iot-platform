package com.eric.governanceApi.governanceApi.service.EventHandlers;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;

@Component
public class DeviceHealthRecoveredHandler extends DeviceHealthHandler {

    public DeviceHealthRecoveredHandler(DeviceRepository deviceRepository, EventRegistryRepository eventRegistryRepository) {
        super(deviceRepository, eventRegistryRepository);
    }

    @Override
    public EventType handles() { return EventType.DEVICE_HEALTH_RECOVERED; }
}
