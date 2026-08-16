package com.eric.governanceApi.governanceApi.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommandTimeoutScheduler {

    private final CommandRecordRepository commandRecordRepository;

    @Value("${app.command.timeout-default-s:300}")
    private long defaultTimeoutS;

    @Value("${app.command.timeout-deep-sleep-cycles:2}")
    private int deepSleepCycles;

    @Value("${app.command.timeout-deep-sleep-margin-s:60}")
    private long deepSleepMarginS;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void processCommandTimeouts() {
        List<CommandRecord> pending = commandRecordRepository.findByStatus(CommandStatus.PENDING);
        if (pending.isEmpty()) return;

        Instant now = Instant.now();
        int expiredCount = 0;

        for (CommandRecord command : pending) {
            long timeoutS = resolveTimeoutSeconds(command);
            if (command.getSentAt() == null) continue;
            Instant deadline = command.getSentAt().plus(Duration.ofSeconds(timeoutS));
            if (now.isBefore(deadline)) continue;

            command.setStatus(CommandStatus.TIMEOUT);
            command.setCompletedAt(now);
            command.setErrorMessage(
                "Timeout atingido: o dispositivo não respondeu em " + timeoutS + "s.");
            expiredCount++;

            Device device = command.getTargetDevice();
            if (device != null) {
                if (device.getStatus() == DeviceStatus.COMMAND_PENDING) {
                    device.setStatus(DeviceStatus.ACTIVE);
                    log.warn("Device ID {} destravado por timeout no comando [{}] (limite={}s)",
                            device.getDeviceId(), command.getCommandType(), timeoutS);
                }

                if (command.getCommandType() == DeviceCommands.UPDATE
                    && device.getAttemptedFirmwareVersion() != null) {
                    device.setAttemptedFirmwareVersion(null);
                    log.warn("Device ID {}: attemptedFirmwareVersion limpo por timeout de UPDATE.",
                            device.getDeviceId());
                }
            }
        }

        if (expiredCount > 0) {
            log.info("Processados {} comandos expirados por timeout.", expiredCount);
        }
    }

    private long resolveTimeoutSeconds(CommandRecord command) {
        Device device = command.getTargetDevice();
        if (device == null) return defaultTimeoutS;
        FirmwareVersion fw = device.getFirmwareVersion();
        if (fw == null || !fw.isDeepSleepEnabled() || fw.getDeepSleepIntervalS() == null) {
            return defaultTimeoutS;
        }
        return (long) fw.getDeepSleepIntervalS() * deepSleepCycles + deepSleepMarginS;
    }
}
