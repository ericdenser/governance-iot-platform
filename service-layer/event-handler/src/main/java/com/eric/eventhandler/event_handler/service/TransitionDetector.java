package com.eric.eventhandler.event_handler.service;

import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.model.DeviceEvent;
import com.eric.eventhandler.event_handler.model.DeviceSnapshot;
import com.eric.eventhandler.event_handler.model.StatusDTO;
import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.repository.SnapshotRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TransitionDetector {

    SnapshotRepository snapshotRepository;
    
    public TransitionDetector (SnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }


    public DeviceEvent process(StatusDTO dto) {

        DeviceSnapshot deviceSnapshot = snapshotRepository.findById(dto.mac()).orElse(null);
                                                            
        if (deviceSnapshot == null) {
            log.info("Ultimo snapshot do macAddress {} é nulo", dto.mac());
        }

        DeviceSnapshot updatedSnapshot = new DeviceSnapshot();
        deviceSnapshot.setMac(dto.mac());
        deviceSnapshot.updateFrom(dto);
        snapshotRepository.save(updatedSnapshot);

        return detectTransaction(dto, deviceSnapshot);
    }


    private DeviceEvent detectTransaction(StatusDTO dto, DeviceSnapshot deviceSnapshot) {

        DeviceState currentState = dto.status();
        DeviceState previousState = deviceSnapshot.getStatus();
        DeviceEvent event = null;

        switch(dto.status()) {
            case PROVISIONING_SUCCESS:

                if (deviceSnapshot.getStatus() != DeviceState.PROVISIONING_SUCCESS){
                    event = buildEvent(EventType.DEVICE_PROVISIONED, dto, previousState, currentState);
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
            .eventName(type)
            .deviceMac(dto.mac())
            .previousStatus(previousStatus)
            .newStatus(newStatus)
            .deviceInfo(dto)
            .timestamp(LocalDateTime.now())
            .build();
    }





    
}
