package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.enums.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.model.dto.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor


// Evento de DeviceProvisioned ocorre quando temos um novo Device sendo provisionado na frota,
// e este Device acaba de notificar via broker que passou em todos os testes e esta pronto para operação.
public class DeviceProvisionedHandler implements DeviceEventHandler {
    private final DeviceRepository deviceRepository;

    @Override
    public EventType handles() { return EventType.DEVICE_PROVISIONED; }

    @Override
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO DEVICE_PROVISIONED PARA DEVICE -> {}", event.deviceMac());

        
        Optional<Device> deviceOptional = deviceRepository.findByMacAddress(event.deviceMac());

        // Device destinatário não encontrado
        if(!deviceOptional.isPresent()) {
            log.info("Device de macAddress {} não encontrado.", event.deviceMac());
            return;
        }

        // Device não possui o status necessário 
        Device device = deviceOptional.get();
        if (device.getStatus() != DeviceStatus.PROVISIONING) {
            log.info("Device de macAddress {} com status inválido para executar o Evento.", event.deviceMac());
            return;
        }

        
        device.setStatus(DeviceStatus.ACTIVE);
        device.setLastSeen(LocalDateTime.now());
        deviceRepository.save(device);
        log.info("Device de macAddress {} registrado na frota com sucesso.", event.deviceMac());
    }
}
