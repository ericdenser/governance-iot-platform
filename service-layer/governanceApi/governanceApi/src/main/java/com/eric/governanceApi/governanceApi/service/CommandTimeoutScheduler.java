package com.eric.governanceApi.governanceApi.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommandTimeoutScheduler {

    private final CommandRecordRepository commandRecordRepository;


    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void processCommandTimeouts() {
        // Define a tolerância. Se o ESP32 não responder em 5 minutos, consideramos Timeout.
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(5);

        List<CommandRecord> expiredCommands = commandRecordRepository
                .findByStatusAndSentAtBefore(CommandStatus.PENDING, timeoutThreshold);

        if (expiredCommands.isEmpty()) {
            return;
        }

        log.info("Encontrados {} comandos estagnados. Processando expiração por Timeout...", expiredCommands.size());

        LocalDateTime now = LocalDateTime.now();

        for (CommandRecord command : expiredCommands) {
            
            command.setStatus(CommandStatus.TIMEOUT);
            command.setCompletedAt(now);
            command.setErrorMessage("Timeout atingido: O dispositivo não respondeu dentro do limite de 5 minutos.");

            Device device = command.getTargetDevice();
            if (device != null) {
                // Só destrava se o status atual ainda for COMMAND_PENDING.
                // Se o device já mudou para outro estado por telemetria, não mexemos.
                if (device.getStatus() == DeviceStatus.COMMAND_PENDING) {
                    device.setStatus(DeviceStatus.ACTIVE);
                    
                    log.warn("Device ID {} destravado automaticamente devido a timeout no comando [{}]", 
                            device.getDeviceId(), command.getCommandType());
                }
            }
        }
    }
}