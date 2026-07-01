package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.Optional;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
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
        device.setLastSeen(event.timestamp());
        eventRegistry.setDevice(device);

        // VALIDAÇÃO 2: O firmware que o device está rodando pós-rollback é o que já está registrado
        // em device.getFirmware() — ele ainda não havia sido atualizado (DeviceUpdatedHandler só altera
        // device.firmware ao confirmar sucesso). Reutilizamos diretamente sem nova query.
        Firmware firmware_atual = device.getFirmware();
        String current_firmware_version = event.deviceInfo().firmware_version();

        if (firmware_atual == null) {
            log.warn("Device de ID {} está rodando uma versão não registrada: v{}.", event.deviceId(), current_firmware_version);
        } else {
            firmware_atual.setDeployCount(firmware_atual.getDeployCount() + 1);
        }

        if (firmware_atual != null) {
            Map<String, Boolean> sensorStatus = new HashMap<>();
            for (FirmwareSensorConfig cfg : firmware_atual.getSensorConfigs()) {
                sensorStatus.put(cfg.getSensor().getName(), false);
            }
            device.setSensorStatus(sensorStatus);
        }

        // VALIDAÇÃO 3: O FIRMWARE QUE SOFREU ROLLBACK É VÁLIDO?
        Firmware rollback_firmware;
        Map<String, Object> params = event.deviceInfo().params();
        String rollback_version = params.get("invalid_ver") != null ? params.get("invalid_ver").toString() : null;

        // Escopo do firmware que falhou = mesmo ownerGroupId do firmware atual do device
        String ownerScope = firmware_atual != null ? firmware_atual.getOwnerGroupId() : null;
        Optional<Firmware> rollback_firmwareOptional = rollback_version != null
                ? firmwareRepository.findByVersionInScope(rollback_version, ownerScope)
                : Optional.empty();

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
