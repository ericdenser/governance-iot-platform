package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceUpdatedHandler implements DeviceEventHandler {
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final FirmwareRepository firmwareRepository;
    private final ErrorRecordRepository errorRecordRepository;

    public EventType handles() { return EventType.DEVICE_UPDATED; }

    // usar transactional
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


        // ESTADO INVÁLIDO
        if (device.getStatus() != DeviceStatus.COMMAND_PENDING) {
            log.warn("Device de ID {} não estava com status de OTA_PENDING, STATUS:", device.getDeviceId(), device.getStatus());
            eventRegistry.setResultMessage("Device de ID " + device.getDeviceId() + "não estava com status de OTA_PENDING.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }

        // atualiza firmware antigo
        Firmware previous_firmware = device.getFirmware();
        if (previous_firmware != null) {
            previous_firmware.setDeployCount(previous_firmware.getDeployCount() - 1);
        }

        // VALIDA FIRMWARE ATUAL
        Firmware current_firmware = null;
        String current_firmware_version = event.deviceInfo().firmware_version();
        Optional<Firmware> current_firmwareOptional = firmwareRepository.findByVersion(current_firmware_version);

        if (!current_firmwareOptional.isPresent()) {
            log.warn("Device de ID {} atualizou para uma versão não registrada [v{}].", event.deviceId(), current_firmware_version);
            eventRegistry.setResultMessage("Device de ID " + device.getDeviceId() + "atualizou para uma versão não registrada [v" + current_firmware_version + "]");
            device.setFirmware(current_firmware); // null para indicar firmware inválido
            eventRegistryRepository.save(eventRegistry);
            return;
        }

        // atualiza firmware atual
        current_firmware = current_firmwareOptional.get();
        current_firmware.setDeployCount(current_firmware.getDeployCount() + 1);
        device.setFirmware(current_firmware);
        device.setStatus(DeviceStatus.ACTIVE);
        
        log.info("Device de ID {} atualizou para a versão [v{}] com sucesso!.", event.deviceId(), current_firmware_version);
        eventRegistry.setResultMessage("Device de ID " + device.getDeviceId() + "atualizou para a versão [v" + current_firmware_version + "] com sucesso!!");
        eventRegistry.setCompleted(true);

        // TODO: Atualiza o mapa de sensores daquele device para bater com do firmware novo

       
        Map<String, Boolean> sensorStatus = new HashMap<>();
        for (FirmwareSensorConfig cfg : current_firmware.getSensorConfigs()) {
                sensorStatus.put(cfg.getSensor().getName(), false);
        }

        device.setSensorStatus(sensorStatus);

        eventRegistryRepository.save(eventRegistry);

        // Atualiza no registro de comandos
        Optional<CommandRecord> pendingCommand = device.getCommandRecords().stream()
                                                .filter(c -> c.getStatus() == CommandStatus.PENDING).findFirst();
        
        if (!pendingCommand.isPresent()) {
            log.warn("Device de ID {} confirmou execução, mas não há comandos PENDING no banco", device.getDeviceId());
            return;
        }

        CommandRecord record = pendingCommand.get();
        record.setCompletedAt(event.timestamp());
        record.setStatus(CommandStatus.COMPLETED_SUCCESS);

        final java.time.Instant ts = event.timestamp();
        errorRecordRepository.findFirstByDevice_DeviceIdAndErrorOrderByReportedAtDesc(device.getDeviceId(), DeviceError.OTA_FAIL)
            .ifPresent(err -> {
                err.setFixedAt(ts);
                err.setStatus(ErrorStatus.FIXED);
            });


    }   

}
