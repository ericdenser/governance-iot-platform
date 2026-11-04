package com.eric.governanceApi.governanceApi.service.EventHandlers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
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
    private final CommandRecordRepository commandRecordRepository;

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

        String reportedVersion = event.deviceInfo().firmwareVersion();
        Map<String, Object> params = event.deviceInfo().params();
        String reason = params.get("reason") != null ? params.get("reason").toString() : null;
        String why = reasonText(reason);
        String invalidSha = params.get("invalid_sha") != null ? params.get("invalid_sha").toString() : null;

        // Detecta cenário
        boolean scenarioA = attemptedVersion != null;

        // Produtos diferentes podem ter o mesmo numero de versao do firmware atual; firmware antigo (sem SHA) cai na versao reportada == anterior.
        boolean scenarioB = attemptedVersion == null
                            && currentFirmware != null
                            && previousVersion != null
                            && (invalidSha != null && !invalidSha.isBlank()
                                    ? invalidSha.equalsIgnoreCase(currentFirmware.getSha256())
                                    : previousVersion.getVersion().equals(reportedVersion));
                            
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
                "Rollback durante OTA: v" + invalidVersion.getVersion() + " " + why +
                ". Device continua em v" + reportedVersion + ".");

            // O device nao confirma o UPDATE nesse caso; fecha o comando pendente como falha
            String failedMsg = "Rollback durante OTA: v" + invalidVersion.getVersion() + " " + why + ".";
            commandRecordRepository
                    .findFirstByTargetDevice_DeviceIdAndStatusAndCommandType(
                            device.getDeviceId(), CommandStatus.PENDING, DeviceCommands.UPDATE)
                    .ifPresent(record -> {
                        record.setStatus(CommandStatus.FAILED);
                        record.setCompletedAt(event.timestamp());
                        record.setErrorMessage(failedMsg);
                    });

        } else if (scenarioB) {
            // Rollback tardio: currentFirmware operou por um tempo e falhou
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
                sensorStatus.put(cfg.getSensor().getCode(), false);
            }
            device.setSensorStatus(sensorStatus);

            log.info("Rollback tardio. Device {} revertido de {} v{} para {} v{}.",
                     device.getDeviceId(),
                     invalidVersion.getFirmware().getFirmwareName(), invalidVersion.getVersion(),
                     previousVersion.getFirmware().getFirmwareName(), previousVersion.getVersion());

            eventRegistry.setResultMessage(
                "Rollback tardio: v" + invalidVersion.getVersion() + " " + why +
                ". Device revertido para v" + previousVersion.getVersion() + ".");

        } else {
            // Device reportou algo que não bate com o tracking do CMDB
            log.warn("Rollback com estado inesperado. Device={}, reportedVersion={}, current={}, previous={}, attempted={}",
                     device.getDeviceId(), reportedVersion,
                     currentFirmware != null ? currentFirmware.getVersion() : "null",
                     previousVersion != null ? previousVersion.getVersion() : "null",
                     attemptedVersion != null ? attemptedVersion.getVersion() : "null");

            eventRegistry.setResultMessage(
                "Rollback com estado inesperado (" + why + "). reportedVersion=v" + reportedVersion + ".");
        }

        // Deprecação da versão inválida se o motivo justificar
        String baseReason = reason != null && reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
        if (invalidVersion != null && ("crashCount".equals(baseReason) || "bootloader_rollback".equals(baseReason))) {
            invalidVersion.setStatus(FirmwareStatus.DEPRECATED);
            eventRegistry.setResultMessage(eventRegistry.getResultMessage()
                + " Versão v" + invalidVersion.getVersion() + " descontinuada (pode ser reativada na tela do firmware).");
            log.info("Versão v{} de '{}' marcada como DEPRECATED (reason={}).",
                     invalidVersion.getVersion(), invalidVersion.getFirmware().getFirmwareName(), reason);
        }

        device.setStatus(DeviceStatus.ACTIVE);
        eventRegistry.setCompleted(scenarioA || scenarioB);
        eventRegistryRepository.save(eventRegistry);
    }

    // reason: "crashCount[:CRASH_TYPE]", "bootloader_rollback" or "unknown"
    static String reasonText(String reason) {
        if (reason == null || reason.isBlank() || "unknown".equals(reason)) return "falhou (motivo não informado pelo device)";
        int i = reason.indexOf(':');
        String base  = i > 0 ? reason.substring(0, i) : reason;
        String crash = i > 0 ? reason.substring(i + 1) : null;
        return switch (base) {
            case "crashCount"          -> "reiniciou por crash várias vezes seguidas" + (crash != null ? " (" + crash + ")" : "");
            case "bootloader_rollback" -> "não iniciou corretamente após o OTA" + (crash != null ? " (" + crash + ")" : "");
            default                    -> "falhou (" + reason + ")";
        };
    }
}
