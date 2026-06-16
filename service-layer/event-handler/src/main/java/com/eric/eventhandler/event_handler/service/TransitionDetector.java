package com.eric.eventhandler.event_handler.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.dto.StatusDTO;
import com.eric.eventhandler.event_handler.model.entity.DeviceEvent;
import com.eric.eventhandler.event_handler.model.entity.DeviceSnapshot;
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
                snapshotRepository.findById(dto.device_id()).orElse(null);

        if (deviceSnapshot == null) {

            log.info("Nenhum snapshot encontrado para device_id {}", dto.device_id());

            deviceSnapshot = new DeviceSnapshot();
            deviceSnapshot.setDeviceId(dto.device_id());
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

                // device em provisioning ainda nao tem registros de status, ou seja, previous state deve ser null
                if (previousState == null) {

                    event = buildEvent(
                            EventType.DEVICE_PROVISIONED,
                            dto,
                            previousState,
                            currentState);
                }

                break;

            case OTA_SUCCESSFUL:

                if (previousState != DeviceState.OTA_SUCCESSFUL) {

                    event = buildEvent(
                            EventType.DEVICE_UPDATED,
                            dto,
                            previousState,
                            currentState);
                }

                break;

            case FIRMWARE_ROLLBACK:

                Map<String, Object> params =
                    dto.params() != null
                        ? new HashMap<>(dto.params())
                        : new HashMap<>();

                boolean modified = false;

                if (!params.containsKey("invalid_ver")) {
                    log.warn("Invalid JSON, parameter [invalid_ver] is missing.");
                    params.put("invalid_ver", 0);
                    modified = true;
                }

                if (!params.containsKey("reason")) {
                    log.warn("Invalid JSON, parameter [reason] is missing.");
                    params.put("reason", "unknown");
                    modified = true;
                }

                if (modified) {
                    StatusDTO newDto = new StatusDTO(
                        dto.device_id(),
                        dto.mac(),
                        dto.firmware_version(),
                        dto.ssid(),
                        currentState,
                        params
                    );

                    event = buildEvent(
                        EventType.DEVICE_FIRMWARE_ROLLBACK,
                        newDto,
                        previousState,
                        currentState
                    );

                    break;
                }

                // fluxo normal quando todos os parâmetros existem
                event = buildEvent(
                    EventType.DEVICE_FIRMWARE_ROLLBACK,
                    dto,
                    previousState,
                    currentState
                );
                break;

            // EM OUTRO DETECTOR (ErrorDetector)
            // case OTA_FAILED:

            //     event = buildEvent(
            //             EventType.DEVICE_UPDATE_FAILED,
            //             dto,
            //             previousState,
            //             currentState);
            //     break;

            case COMMAND_COMPLETE:

                event = buildEvent(
                    EventType.DEVICE_COMMAND_COMPLETE,
                    dto,
                    previousState, 
                    currentState);


            default:
                break;

        }
        return event;
    }



    private DeviceEvent buildEvent(EventType type, StatusDTO dto,
                                DeviceState previousStatus, DeviceState newStatus) {
    log.info("Evento detectado: {} | DeviceID: {} | {} → {}",
                type, dto.device_id(), previousStatus, newStatus);

    return DeviceEvent.builder()
            .eventType(type)
            .deviceId(dto.device_id())
            .previousStatus(previousStatus)
            .newStatus(newStatus)
            .deviceInfo(dto)
            .timestamp(Instant.now())
            .build();
    }





    
}
