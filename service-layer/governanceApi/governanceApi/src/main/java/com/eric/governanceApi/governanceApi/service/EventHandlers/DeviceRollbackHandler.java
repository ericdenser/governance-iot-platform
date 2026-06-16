package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.dto.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor


// Evento de DeviceRollback ocorre quando temos um device X que por um motivo Y executou a lógica de rollback
// e retornou para uma partição anterior.
public class DeviceRollbackHandler implements DeviceEventHandler {
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final FirmwareRepository firmwareRepository;

    @Override
    public EventType handles() { return EventType.DEVICE_FIRMWARE_ROLLBACK; }

    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO DEVICE_ROLLBACK PARA DEVICE ID-> {}", event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setUploadedAt(event.timestamp());
        
        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(event.deviceId());

        // VALIDAÇÃO 1: DEVICE REMETENTE EXISTE?
        if(!deviceOptional.isPresent()) {
            log.warn("Device de ID {} não encontrado.", event.deviceId());
            eventRegistry.setDevice(null);
            eventRegistry.setResultMessage("Device de ID ["+ event.deviceId() + "] não encontrado.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }
        // persiste
        Device device = deviceOptional.get();
        device.setLastSeen(LocalDateTime.now());
        eventRegistry.setDevice(device);
        Firmware firmware_atual = null;

        // VALIDAÇÃO 2: FIRMWARE QUE O DEVICE ATUAL ESTA RODANDO EXISTE?
        String current_firmware_version = event.deviceInfo().firmware_version();
        Optional<Firmware> firmware_atualOptional = firmwareRepository.findByVersion(current_firmware_version);

        // se essa versão não existe
        if (!firmware_atualOptional.isPresent()) {
            log.warn("Device de ID {} está rodando uma versão não registrada: v{}.", event.deviceId(), current_firmware_version);
            device.setFirmware(firmware_atual); // registra null como inválido
        } else {
            firmware_atual = firmware_atualOptional.get();

            device.setFirmware(firmware_atual); // substitui o antigo (que sofreu rollback) pelo atual
            firmware_atual.setDeployCount(firmware_atual.getDeployCount() + 1); // incrementa deploy count
        }

        // VALIDAÇÃO 3: O FIRMWARE QUE SOFREU ROLLBACK É VÁLIDO?
        Firmware rollback_firmware;
        Map<String, Object> params = event.deviceInfo().params();
        String rollback_version = params.get("invalid_ver") != null ? params.get("invalid_ver").toString() : null;

        // busca nos firmwares registrados
        Optional<Firmware> rollback_firmwareOptional = rollback_version != null ? firmwareRepository.findByVersion(rollback_version) : Optional.empty();

        boolean firmware_not_informed = rollback_version == null || rollback_version.isEmpty();
        boolean firmware_not_found = rollback_firmwareOptional.isEmpty();

        // Se o firmware não foi informado pelo device, ou o firmware não estava registrado no cmdb
        if (firmware_not_found || firmware_not_informed) {
            log.warn("Device de ID {} fez rollback de uma versão inválida (não registrada ou não informada). [v{}]", rollback_version);
            eventRegistry.setResultMessage("Device de ID ["+ event.deviceId() + "] fez rollback de versão inválida (não registrada ou não informada) [v" + rollback_version +"]");
            eventRegistryRepository.save(eventRegistry);
            return;
        }
        
        // persiste
        rollback_firmware = rollback_firmwareOptional.get();
        rollback_firmware.setDeployCount(rollback_firmware.getDeployCount() - 1);
        String reason = params.get("reason").toString();
        eventRegistry.setResultMessage("Device de ID: ["+ event.deviceId() + "] fez rollback de versão: [v" + rollback_version +"] por reason [" + reason + "].");
        
        if (reason.equals("crashCount") || reason.equals("bootloader_rollback")) {
            rollback_firmware.setStatus(FirmwareStatus.DEPRECATED);
        }

        device.setStatus(DeviceStatus.ACTIVE);
        eventRegistry.setCompleted(true);
        eventRegistryRepository.save(eventRegistry);
        log.info("Device de ID [{}] fez rollback da versão [v{}] para a versão [v{}] por reason [{}].", device.getDeviceId(), rollback_version, current_firmware_version, reason);
        return;
    }
}
