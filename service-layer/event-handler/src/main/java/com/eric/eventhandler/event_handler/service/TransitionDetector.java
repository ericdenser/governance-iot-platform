package com.eric.eventhandler.event_handler.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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

    private final SnapshotRepository snapshotRepository;

    // Último estado conhecido por device, em memória.
    private final Map<String, CachedState> lastKnown = new ConcurrentHashMap<>();

    public TransitionDetector (SnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }


    // synchronized: a thread do container e a do PEL sweep podem processar
    // status do mesmo device ao mesmo tempo; serializar evita evento duplicado.
    public synchronized DeviceEvent process(StatusDTO dto) {

        CachedState previous = lastKnown.get(dto.deviceId());
        if (previous == null) {
            // única leitura do Postgres por device após boot
            previous = snapshotRepository.findById(dto.deviceId())
                    .map(CachedState::from)
                    .orElseGet(() -> {
                        log.info("Nenhum snapshot encontrado para device_id {}", dto.deviceId());
                        return CachedState.MISSING;
                    });
        }

        DeviceState previousState = previous.status();
        String previousSensors = previous.activeSensors();

        // Persiste só quando algo relevante mudou
        CachedState current = CachedState.from(dto);
        if (!current.equals(previous)) {
            DeviceSnapshot deviceSnapshot = new DeviceSnapshot();
            deviceSnapshot.setDeviceId(dto.deviceId());
            deviceSnapshot.updateFrom(dto);
            snapshotRepository.save(deviceSnapshot);
        }
        lastKnown.put(dto.deviceId(), current);

        // Check transição
        DeviceEvent stateEvent = detectTransition(dto, previousState);
        if (stateEvent != null) return stateEvent;

        // Segundo check - só dispara se sensores mudaram e operational
        return detectSensorChange(dto, previousSensors);
    }

    private DeviceEvent detectSensorChange(StatusDTO dto, String previousSensors) {
        String currentSensors = dto.activeSensors();

        boolean emptyReports = (currentSensors == null || currentSensors.isBlank()) 
                                && (previousSensors == null || previousSensors.isBlank());

        boolean sensorsChanged = !Objects.equals(previousSensors, currentSensors);
        boolean isOperational = dto.status() == DeviceState.OPERATIONAL;

        if (isOperational && sensorsChanged && !emptyReports) {
            log.info("Mudança de sensores detectada: [{}] -> [{}]", previousSensors, currentSensors);
            return buildEvent(EventType.DEVICE_SENSOR_STATUS_CHANGED, dto, dto.status(), dto.status());
        }
        return null;
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
                        dto.deviceId(),
                        dto.mac(),
                        dto.firmwareVersion(),
                        dto.ssid(),
                        currentState,
                        params,
                        dto.deviceTimestamp(),
                        dto.activeSensors()
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
                type, dto.deviceId(), previousStatus, newStatus);

    return DeviceEvent.builder()
            .eventType(type)
            .deviceId(dto.deviceId())
            .previousStatus(previousStatus)
            .newStatus(newStatus)
            .deviceInfo(dto)
            .timestamp(dto.deviceTimestamp() != null ? dto.deviceTimestamp() : Instant.now())
            .build();
    }



 // Campos comparados pra decidir se o snapshot precisa ir pro Postgres.
    private record CachedState(DeviceState status, String activeSensors,
                               String firmwareVersion, String ssid, String mac) {

        static final CachedState MISSING = new CachedState(null, null, null, null, null);

        static CachedState from(DeviceSnapshot s) {
            return new CachedState(s.getStatus(), s.getActiveSensors(),
                    s.getFirmwareVersion(), s.getSsid(), s.getMac());
        }

        static CachedState from(StatusDTO dto) {
            return new CachedState(dto.status(), dto.activeSensors(),
                    dto.firmwareVersion(), dto.ssid(), dto.mac());
        }
    }

    
}
