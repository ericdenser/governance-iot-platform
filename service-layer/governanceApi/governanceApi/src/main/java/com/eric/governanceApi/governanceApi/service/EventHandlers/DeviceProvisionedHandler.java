package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.dto.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor


// Evento de DeviceProvisioned ocorre quando temos um novo Device sendo provisionado na frota,
// e este Device acaba de postar no broker que passou em todos os testes e esta pronto para operação.
public class DeviceProvisionedHandler implements DeviceEventHandler {
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;

    @Override
    public EventType handles() { return EventType.DEVICE_PROVISIONED; }

    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO DEVICE_PROVISIONED PARA DEVICE ID-> {}", event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setUploadedAt(event.timestamp());
        
        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(event.deviceId());
        

        // Device remetente não encontrado
        if(!deviceOptional.isPresent()) {
            log.info("Device de ID {} não encontrado.", event.deviceId());
            eventRegistry.setDevice(null);
            eventRegistry.setResultMessage("Device de ID ["+ event.deviceId() + "] não encontrado.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }   

        Device device = deviceOptional.get();

        eventRegistry.setDevice(device);
        device.setLastSeen(LocalDateTime.now());

        // Device não possui o status necessário
        if (device.getStatus() != DeviceStatus.PROVISIONING) {
            log.info("Device de ID {} com status inválido para executar o Evento. Status: {}", event.deviceId(), device.getStatus());
            eventRegistry.setResultMessage("Device de ID ["+ event.deviceId() + "] com status inválido para executar o Evento.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }

        device.setStatus(DeviceStatus.ACTIVE);

        // Contabiliza que este device está rodando o provisioning firmware
        if (device.getFirmware() != null) {
            device.getFirmware().setDeployCount(device.getFirmware().getDeployCount() + 1);
        }

        eventRegistry.setCompleted(true);
        eventRegistryRepository.save(eventRegistry);
        log.info("Device de ID {} registrado na frota com sucesso.", event.deviceId());
        return;
    }
}
