package com.eric.governanceApi.governanceApi.service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.service.HotStateService.LiveState;

import lombok.extern.slf4j.Slf4j;

// Consolida periodicamente o hot state (Redis Hash device:{id}:last) no cmdb:
// last_seen, last_latitude, last_longitude e last_seen_persisted_at.

//(ShedLock futuramente para mais instancias).
 
@Component
@Slf4j
public class HotStatePersistenceScheduler {

    private static final int BATCH_SIZE = 500;

    // WHERE status != 'REVOKED': devices revogados são terminais — não devem
    // ser sobrescritos pelo hot state stale do Redis (última telemetria que
    // chegou antes/durante a revogação). Sem esse guard, o scheduler restaura
    // status=ACTIVE em cima do REVOKED a cada 5 minutos.
    private static final String UPDATE_SQL = """
            UPDATE devices
               SET last_seen = ?,
                   last_latitude = COALESCE(?, last_latitude),
                   last_longitude = COALESCE(?, last_longitude),
                   status = COALESCE(?, status),
                   last_seen_persisted_at = ?
             WHERE device_id = ?
               AND status <> 'REVOKED'
            """;

    private final DeviceRepository deviceRepository;
    private final HotStateService hotStateService;
    private final JdbcTemplate jdbcTemplate;

    private volatile Instant cutoff;

    public HotStatePersistenceScheduler(DeviceRepository deviceRepository,
                                        HotStateService hotStateService,
                                        JdbcTemplate jdbcTemplate) {
        this.deviceRepository = deviceRepository;
        this.hotStateService = hotStateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @SuppressWarnings("null")
    @Scheduled(fixedDelayString = "${app.hotstate.persist-interval-ms:300000}")
    public void persistHotState() {
        Instant runStarted = Instant.now();

        List<String> deviceIds = deviceRepository.findAllDeviceIds();
        if (deviceIds.isEmpty()) {
            cutoff = runStarted;
            return;
        }

        Map<String, LiveState> live = hotStateService.getLiveBulk(deviceIds);

        List<DirtyDevice> dirty = live.entrySet().stream()
                .filter(e -> isDirty(e.getValue()))
                .map(e -> new DirtyDevice(
                        e.getKey(),
                        e.getValue().lastSeen(),
                        e.getValue().latitude(),
                        e.getValue().longitude(),
                        e.getValue().status()))
                // ordem estável por deviceId evita deadlock com writers concorrentes
                .sorted(Comparator.comparing(DirtyDevice::deviceId))
                .toList();

        if (dirty.isEmpty()) {
            cutoff = runStarted;
            log.debug("HotState persist: scanned={} dirty=0", deviceIds.size());
            return;
        }

        Timestamp persistedAt = Timestamp.from(runStarted);
        jdbcTemplate.batchUpdate(UPDATE_SQL, dirty, BATCH_SIZE, (ps, d) -> {
            ps.setTimestamp(1, Timestamp.from(d.lastSeen()));
            if (d.latitude() != null) ps.setDouble(2, d.latitude()); else ps.setNull(2, Types.DOUBLE);
            if (d.longitude() != null) ps.setDouble(3, d.longitude()); else ps.setNull(3, Types.DOUBLE);
            if (d.status() != null) ps.setString(4, d.status()); else ps.setNull(4, Types.VARCHAR);
            ps.setTimestamp(5, persistedAt);
            ps.setString(6, d.deviceId());
        });

        // Só avança o cutoff após o batch — se falhar, o próximo run reprocessa.
        cutoff = runStarted;

        log.info("HotState persist: scanned={} dirty={} duration={}ms",
                deviceIds.size(), dirty.size(),
                Duration.between(runStarted, Instant.now()).toMillis());
    }

    private boolean isDirty(LiveState state) {
        if (state.lastSeen() == null) {
            return false;
        }
        Instant since = cutoff;
        return since == null || !state.lastSeen().isBefore(since);
    }

    private record DirtyDevice(String deviceId, Instant lastSeen, Double latitude, Double longitude, String status) {
    }
}
