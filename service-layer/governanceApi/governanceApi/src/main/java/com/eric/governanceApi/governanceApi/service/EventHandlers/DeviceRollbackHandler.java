package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
// Evento DeviceRollback: bootloader do ESP executou lógica de rollback automático
// (crash count exceded, verify_rollback detectou app inválida, etc).
// NÃO é o mesmo que rollback comandado pelo usuário (esse cai em DeviceCommandHandler).
//
// Dois cenários distintos, tratados via device.attemptedFirmwareVersion:
//   Cenário A — Rollback DURANTE OTA (attempted != null)
//     Device nunca chegou a promover o novo firmware. Continua no currentFirmware.
//   Cenário B — Rollback TARDIO (attempted == null)
//     currentFirmware operou por um tempo e falhou. Volta pro previousFirmwareVersion.
public class DeviceRollbackHandler implements DeviceEventHandler {
    private final DeviceRepository deviceRepository;
    private final EventRegistryRepository eventRegistryRepository;

    @Override
    public EventType handles() { return EventType.DEVICE_FIRMWARE_ROLLBACK; }

    @SuppressWarnings("null")
    @Override
    @Transactional
    public void process(DeviceEventWebhookDTO event) {
        log.info("PROCESSANDO EVENTO DEVICE_ROLLBACK PARA DEVICE ID-> {}", event.deviceId());

        EventRegistry eventRegistry = new EventRegistry();
        eventRegistry.setEventName(event.eventType());
        eventRegistry.setPreviousStatus(event.previousStatus());
        eventRegistry.setNewStatus(event.newStatus());
        eventRegistry.setOcurredAt(event.timestamp());

        Optional<Device> deviceOptional = deviceRepository.findByDeviceId(event.deviceId());

        if (!deviceOptional.isPresent()) {
            log.warn("Device de ID {} não encontrado.", event.deviceId());
            eventRegistry.setDevice(null);
            eventRegistry.setResultMessage("Device de ID [" + event.deviceId() + "] não encontrado.");
            eventRegistryRepository.save(eventRegistry);
            return;
        }

        Device device = deviceOptional.get();
        device.setLastSeen(event.timestamp());
        eventRegistry.setDevice(device);

        FirmwareVersion currentFirmware  = device.getFirmwareVersion();
        FirmwareVersion previousVersion  = device.getPreviousFirmwareVersion();
        FirmwareVersion attemptedVersion = device.getAttemptedFirmwareVersion();

        String reportedVersion = event.deviceInfo().firmware_version();
        Map<String, Object> params = event.deviceInfo().params();
        String reason = params.get("reason") != null ? params.get("reason").toString() : null;

        // Detecta cenário
        boolean scenarioA = attemptedVersion != null;

        boolean scenarioB = attemptedVersion == null
                            && currentFirmware != null
                            && previousVersion != null
                            && !currentFirmware.getVersion().equals(reportedVersion)
                            && previousVersion.getVersion().equals(reportedVersion);
                            
        FirmwareVersion invalidVersion = null;

        if (scenarioA) {
            // Rollback durante OTA: device continua rodando currentFirmware.
            // Só limpa attempted e marca a versão que falhou.
            invalidVersion = attemptedVersion;
            device.setAttemptedFirmwareVersion(null);

            log.info("Rollback durante OTA. Device {} continua em v{}. Versão que falhou: v{} ({}).",
                     device.getDeviceId(), reportedVersion,
                     invalidVersion.getVersion(),
                     invalidVersion.getFirmware().getFirmwareName());

            eventRegistry.setResultMessage(
                "Rollback durante OTA. Device continua em v" + reportedVersion +
                ". Versão que falhou instalar: v" + invalidVersion.getVersion() + ".");

        } else if (scenarioB) {
            // Rollback tardio: currentFirmware operou por um tempo e falhou.
            // Swap current ↔ previous, ajusta deployCounts.
            invalidVersion = currentFirmware;

            currentFirmware.setDeployCount(currentFirmware.getDeployCount() - 1);
            if (currentFirmware.getDeployCount() <= 0 && currentFirmware.getStatus() != FirmwareStatus.DEPRECATED) {
                currentFirmware.setStatus(FirmwareStatus.STAGED);
            }
            previousVersion.setDeployCount(previousVersion.getDeployCount() + 1);
            if (previousVersion.getDeployCount() >= 1 && previousVersion.getStatus() != FirmwareStatus.DEPRECATED) {
                previousVersion.setStatus(FirmwareStatus.DEPLOYED);
            }

            device.setFirmwareVersion(previousVersion);
            device.setPreviousFirmwareVersion(null);

            // Sensor status volta pra config da versão pra qual voltamos
            Map<String, Boolean> sensorStatus = new HashMap<>();
            for (FirmwareSensorConfig cfg : previousVersion.getSensorConfigs()) {
                sensorStatus.put(cfg.getSensor().getName(), false);
            }
            device.setSensorStatus(sensorStatus);

            log.info("Rollback tardio. Device {} revertido de {} v{} para {} v{}.",
                     device.getDeviceId(),
                     invalidVersion.getFirmware().getFirmwareName(), invalidVersion.getVersion(),
                     previousVersion.getFirmware().getFirmwareName(), previousVersion.getVersion());

            eventRegistry.setResultMessage(
                "Rollback tardio. Device revertido para v" + previousVersion.getVersion() + ".");

        } else {
            // Device reportou algo que não bate com o tracking do CMDB
            log.warn("Rollback com estado inesperado. Device={}, reportedVersion={}, current={}, previous={}, attempted={}",
                     device.getDeviceId(), reportedVersion,
                     currentFirmware != null ? currentFirmware.getVersion() : "null",
                     previousVersion != null ? previousVersion.getVersion() : "null",
                     attemptedVersion != null ? attemptedVersion.getVersion() : "null");

            eventRegistry.setResultMessage(
                "Rollback com estado inesperado. reportedVersion=v" + reportedVersion + ".");
        }

        // Deprecação da versão inválida se o motivo justificar
        if (invalidVersion != null && ("crashCount".equals(reason) || "bootloader_rollback".equals(reason))) {
            invalidVersion.setStatus(FirmwareStatus.DEPRECATED);
            log.info("Versão v{} de '{}' marcada como DEPRECATED (reason={}).",
                     invalidVersion.getVersion(), invalidVersion.getFirmware().getFirmwareName(), reason);
        }

        device.setStatus(DeviceStatus.ACTIVE);
        eventRegistry.setCompleted(scenarioA || scenarioB);
        eventRegistryRepository.save(eventRegistry);
    }
}
