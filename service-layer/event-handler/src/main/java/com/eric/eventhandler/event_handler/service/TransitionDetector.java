package com.eric.eventhandler.event_handler.service;

import java.time.Instant;
import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.model.dto.StatusDTO;
import com.eric.eventhandler.event_handler.model.entity.DeviceEvent;
import com.eric.eventhandler.event_handler.model.entity.DeviceSnapshot;
import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.repository.SnapshotRepository;
import lombok.extern.slf4j.Slf4j;


// Detecta se o ultimo estado do device gera algum evento
@Service
@Slf4j
public class TransitionDetector {

    SnapshotRepository snapshotRepository;
    
    public TransitionDetector (SnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }


    public DeviceEvent process(StatusDTO dto) {

        DeviceSnapshot deviceSnapshot =
                snapshotRepository.findById(dto.mac()).orElse(null);

        if (deviceSnapshot == null) {

            log.info("Nenhum snapshot encontrado para MAC {}", dto.mac());

            deviceSnapshot = new DeviceSnapshot();
            deviceSnapshot.setMac(dto.mac());
        }

        DeviceState previousState = deviceSnapshot.getStatus();

        deviceSnapshot.updateFrom(dto);

        snapshotRepository.save(deviceSnapshot);

        return detectTransition(dto, previousState);
    }


    private DeviceEvent detectTransition(StatusDTO dto, DeviceState previousState) {

        DeviceState currentState = dto.status();

        DeviceEvent event = null;

        switch (currentState) {

            case PROVISIONING_SUCCESS:

                if (previousState != DeviceState.PROVISIONING_SUCCESS) {

                    event = buildEvent(
                            EventType.DEVICE_PROVISIONED,
                            dto,
                            previousState,
                            currentState);
                }

                break;

            default:
                break;
        }

        return event;
    }



    private DeviceEvent buildEvent(EventType type, StatusDTO dto,
                                DeviceState previousStatus, DeviceState newStatus) {
    log.info("Evento detectado: {} | MAC: {} | {} → {}",
                type, dto.mac(), previousStatus, newStatus);

    return DeviceEvent.builder()
            .eventType(type)
            .deviceMac(dto.mac())
            .previousStatus(previousStatus)
            .newStatus(newStatus)
            .deviceInfo(dto)
            .timestamp(Instant.now())
            .build();
    }





    
}
