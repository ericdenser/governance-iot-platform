package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareVersionRepository;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceUpdatedHandler implements DeviceEventHandler {
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final ErrorRecordRepository errorRecordRepository;
    private final CommandRecordRepository commandRecordRepository;
    private final FirmwareVersionRepository firmwareVersionRepository;

    public EventType handles() { return EventType.DEVICE_UPDATED; }

    // usar transactional
    @Transactional
    public void process(DeviceEventWebhookDTO event) {

        log.info("PROCESSANDO EVENTO {} PARA DEVICE ID-> {}", this.handles().toString(), event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setOcurredAt(event.timestamp());
        
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
        FirmwareVersion previous_firmware = device.getFirmwareVersion();
        if (previous_firmware != null) {
            previous_firmware.setDeployCount(previous_firmware.getDeployCount() - 1);
            if (previous_firmware.getDeployCount() <= 0 && previous_firmware.getStatus() != FirmwareStatus.DEPRECATED) {
                previous_firmware.setStatus(FirmwareStatus.STAGED);
            }
        }

        // Resolve o firmware alvo pelo firmwareId gravado no CommandRecord 
        FirmwareVersion current_firmware = null;
        String current_firmware_version = event.deviceInfo().firmware_version();

        Optional<CommandRecord> pendingForFirmware = commandRecordRepository
                .findFirstByTargetDevice_DeviceIdAndStatus(device.getDeviceId(), CommandStatus.PENDING);

        @SuppressWarnings("null")
        String targetVersionId = pendingForFirmware.map(CommandRecord::getTargetVersionId).orElse(null);
        Optional<FirmwareVersion> newVersion = targetVersionId != null
                ? firmwareVersionRepository.findByFirmwareVersionId(targetVersionId)
                : Optional.empty();

        if (!newVersion.isPresent()) {
            log.warn("Device de ID {} atualizou para uma versão não registrada [v{}].", event.deviceId(), current_firmware_version);
            eventRegistry.setResultMessage("Device de ID " + device.getDeviceId() + "atualizou para uma versão não registrada [v" + current_firmware_version + "]");
            device.setFirmwareVersion(current_firmware); // null para indicar firmware inválido
            eventRegistryRepository.save(eventRegistry);
            return;
        }

        // Promove: previous = current, current = new (attempted), attempted = null
        current_firmware = newVersion.get();

        device.setPreviousFirmwareVersion(previous_firmware); // pode ser null no provisioning
        device.setFirmwareVersion(current_firmware);
        device.setAttemptedFirmwareVersion(null); // OTA concluído com sucesso

        current_firmware.setDeployCount(current_firmware.getDeployCount() + 1);
        if (current_firmware.getDeployCount() >= 1) {
            current_firmware.setStatus(FirmwareStatus.DEPLOYED);
        }
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

        // Atualiza no registro de comandos (reutiliza o Optional já carregado acima)
        if (!pendingForFirmware.isPresent()) {
            log.warn("Device de ID {} confirmou execução, mas não há comandos PENDING no banco", device.getDeviceId());
            return;
        }

        CommandRecord record = pendingForFirmware.get();
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
