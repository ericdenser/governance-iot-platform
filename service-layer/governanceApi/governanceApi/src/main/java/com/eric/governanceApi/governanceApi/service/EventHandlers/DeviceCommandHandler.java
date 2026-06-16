package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.dto.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceCommandHandler implements DeviceEventHandler{
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final FirmwareRepository firmwareRepository;


    @Override
    public EventType handles() {return EventType.DEVICE_COMMAND_COMPLETE; }

    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO {} PARA DEVICE ID-> {}", this.handles().toString(), event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setUploadedAt(event.timestamp());

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
        device.setLastSeen(LocalDateTime.now());
        eventRegistry.setDevice(device);

        // Se não estava com status válido
        if (device.getStatus() != DeviceStatus.COMMAND_PENDING) {
            log.warn("Device de ID {} não possuia um comando pendente. Status atual: [{}] Status esperado: [COMMAND_PENDING]", 
                     device.getDeviceId(), device.getStatus());
            
            eventRegistry.setResultMessage("Device de ID [" + device.getDeviceId() + "] não possuia status válido [COMMAND_PENDING].");
            eventRegistryRepository.save(eventRegistry);
            return;
        }


        Optional<CommandRecord> pendingCommand = device.getCommandRecords().stream()
                                                .filter(c -> c.getStatus() == CommandStatus.PENDING).findFirst();
        
        if (!pendingCommand.isPresent()) {
            log.warn("Device de ID {} confirmou execução, mas não há comandos PENDING no banco", device.getDeviceId());

            device.setStatus(DeviceStatus.ACTIVE);
            return;
        }

        CommandRecord record = pendingCommand.get();
        record.setCompletedAt(LocalDateTime.now());
        record.setStatus(CommandStatus.COMPLETED_SUCCESS);
        device.setStatus(DeviceStatus.ACTIVE);
    

        if (record.getCommandType() == DeviceCommands.FIRMWARE_ROLLBACK) {

            Firmware currentFirmware = device.getFirmware();
            currentFirmware.decrementDeployCount();

            String reportedVersion = event.deviceInfo().firmware_version();
            Optional<Firmware> firmwareOpt = firmwareRepository.findByVersion(reportedVersion);
            if (firmwareOpt.isPresent()) {
                device.setFirmware(firmwareOpt.get());
                firmwareOpt.get().incrementDeployCount();
                log.info("Rollback forçado confirmado. Device {} atualizado para firmware v{}.", device.getDeviceId(), reportedVersion);
            } else {
                log.warn("Rollback forçado confirmado, mas firmware v{} não está registrado no CMDB.", reportedVersion);
                device.setFirmware(null);
            }
        }

        eventRegistry.setCompleted(true);
        eventRegistry.setResultMessage("Device de ID " + device.getDeviceId() + " executou o comando [" + record.getCommandType().toString() + "] com sucesso!");

        eventRegistryRepository.save(eventRegistry);

        log.info("Device de ID {} executou o comando {} com sucesso.", event.deviceId(), record.getCommandType().toString());

    }
}
