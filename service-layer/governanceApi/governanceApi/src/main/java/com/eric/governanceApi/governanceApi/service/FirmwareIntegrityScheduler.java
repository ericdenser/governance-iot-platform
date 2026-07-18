package com.eric.governanceApi.governanceApi.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.repository.FirmwareVersionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;

@Component
@Slf4j
@RequiredArgsConstructor
public class FirmwareIntegrityScheduler {

    // Out of the cycle (bootloader/partition-table of flash package)
    private static final String PLATFORM_PREFIX = "platform/";

    // Delete orphan versions after 24h
    private static final Duration ORPHAN_GRACE = Duration.ofHours(24);

    private final FirmwareVersionRepository firmwareVersionRepository;
    private final FirmwareStorageService storageService;

    
     // Version exist in CMDB but binary not in MinIO -> CORRUPTED
     // Binary is back (restore from backup / reupload directly on bucket) -> restore status.
    @Scheduled(initialDelayString = "${firmware.integrity.initial-delay-ms:30000}",
               fixedDelayString = "${firmware.integrity.check-interval-ms:3600000}")
    @Transactional
    public void checkIntegrity() {
        Map<String, Instant> storedObjects;
        try {
            storedObjects = storageService.listAllObjects();
        } catch (SdkException e) {
            log.error("Integrity check aborted — storage unavaiable: {}", e.getMessage());
            return;
        }

        List<FirmwareVersion> versions = firmwareVersionRepository.findAll();
        int corrupted = 0, restored = 0;

        for (FirmwareVersion v : versions) {
            boolean present = storedObjects.containsKey(v.getFilename());

            if (!present && (v.getStatus() == FirmwareStatus.STAGED || v.getStatus() == FirmwareStatus.DEPLOYED)) {
                v.setStatus(FirmwareStatus.CORRUPTED);
                corrupted++;
                log.error("Integrity: binary '{}' of version v{} ({}) absent in storage -> CORRUPTED.",
                        v.getFilename(), v.getVersion(), v.getFirmwareVersionId());
            } else if (present && v.getStatus() == FirmwareStatus.CORRUPTED) {
                v.setStatus(v.getDeployCount() > 0 ? FirmwareStatus.DEPLOYED : FirmwareStatus.STAGED);
                restored++;
                log.info("Integrity: binary '{}' of version v{} returned to storage — status restored to {}.",
                        v.getFilename(), v.getVersion(), v.getStatus());
            }
        }

        Set<String> orphans = findOrphans(storedObjects.keySet(), versions);
        for (String key : orphans) {
            log.warn("Integrity: orphan object in storage with no version on CMDB: '{}'.", key);
        }

        if (corrupted > 0 || restored > 0 || !orphans.isEmpty()) {
            log.info("Integrity check: {} version(s) set as CORRUPTED, {} restored(s), {} orphan(s) in storage.",
                    corrupted, restored, orphans.size());
        }
    }

    // Remove from storage objects that doesnt belong to any version on CMDB
    @Scheduled(initialDelayString = "${firmware.integrity.orphan-initial-delay-ms:120000}",
               fixedDelayString = "${firmware.integrity.orphan-cleanup-interval-ms:604800000}")
    @Transactional(readOnly = true)
    public void cleanupOrphans() {
        Map<String, Instant> storedObjects;
        try {
            storedObjects = storageService.listAllObjects();
        } catch (SdkException e) {
            log.error("Orphan cleanup aborted — storage unavaiable: {}", e.getMessage());
            return;
        }

        Set<String> orphans = findOrphans(storedObjects.keySet(), firmwareVersionRepository.findAll());
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);

        for (String key : orphans) {
            Instant lastModified = storedObjects.get(key);
            if (lastModified.isAfter(cutoff)) continue;
            try {
                storageService.delete(key);
                log.info("Orphan cleanup: object '{}' removed from storage (lastModified={}).", key, lastModified);
            } catch (SdkException e) {
                log.warn("Orphan cleanup: failed at removing '{}': {}", key, e.getMessage());
            }
        }
    }

    private Set<String> findOrphans(Set<String> storedKeys, List<FirmwareVersion> versions) {
        Set<String> known = new HashSet<>();
        for (FirmwareVersion v : versions) known.add(v.getFilename());

        Set<String> orphans = new HashSet<>();
        for (String key : storedKeys) {
            if (key.startsWith(PLATFORM_PREFIX)) continue;
            if (!known.contains(key)) orphans.add(key);
        }
        return orphans;
    }
}
