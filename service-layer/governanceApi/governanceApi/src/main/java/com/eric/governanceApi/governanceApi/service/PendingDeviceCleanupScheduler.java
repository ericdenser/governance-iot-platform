package com.eric.governanceApi.governanceApi.service;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupMembershipRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ProvisioningTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class PendingDeviceCleanupScheduler {

    private final ProvisioningTokenRepository tokenRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceGroupMembershipRepository membershipRepository;

    // Roda a cada 5 minutos. Remove devices criados no generate-package cujo token
    // expirou sem que o ESP32 tenha completado o provisioning (status ainda PENDING).
    @Scheduled(fixedDelayString = "${provisioning.cleanup-interval-ms:300000}")
    @Transactional
    public void cleanupExpiredPendingDevices() {
        List<ProvisioningToken> expired = tokenRepository.findByExpBeforeAndUsedFalse(Instant.now());

        if (expired.isEmpty()) return;

        log.info("Cleanup: {} token(s) expirado(s) sem uso encontrado(s).", expired.size());

        for (ProvisioningToken token : expired) {
            Device device = token.getDevice();
            if (device == null) continue;

            if (device.getStatus() != DeviceStatus.PENDING) {
                // Device avançou de estado por outro caminho — não remover.
                log.warn("Token expirado mas device {} não está PENDING (status={}). Ignorado.",
                        device.getDeviceId(), device.getStatus());
                continue;
            }

            log.info("Removendo device fantasma {} ('{}') — token expirou sem provisioning.",
                    device.getDeviceId(), device.getName());

            membershipRepository.deleteByDeviceId(device.getId());

            deviceRepository.delete(device);
        }
    }
}
