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

/**
 * Consolida periodicamente o hot state (Redis Hash device:{id}:last) no cmdb:
 * last_seen, last_latitude, last_longitude e last_seen_persisted_at.
 *
 * Estratégia (Obj 11 Fase D4): sem SELECT de comparação no Postgres — um device
 * é "sujo" quando o last_seen do Hash é >= cutoff (timestamp do último run
 * bem-sucedido). No primeiro run pós-boot o cutoff é null e tudo que tem hot
 * state é persistido (full sync), o que auto-corrige restarts do govApi.
 *
 * Escrita via UPDATE cirúrgico em batch (JDBC) — nunca via entidade JPA, pra
 * não sobrescrever mudanças concorrentes de status/firmware feitas pelos
 * handlers de evento.
 *
 * Assume instância única do govApi. Se escalar horizontal, precisa de lock
 * distribuído (ex: ShedLock).
 */
@Component
@Slf4j
public class HotStatePersistenceScheduler {

    private static final int BATCH_SIZE = 500;

    private static final String UPDATE_SQL = """
            UPDATE devices
               SET last_seen = ?,
                   last_latitude = COALESCE(?, last_latitude),
                   last_longitude = COALESCE(?, last_longitude),
                   last_seen_persisted_at = ?
             WHERE device_id = ?
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
                        e.getValue().longitude()))
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
            ps.setTimestamp(4, persistedAt);
            ps.setString(5, d.deviceId());
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

    private record DirtyDevice(String deviceId, Instant lastSeen, Double latitude, Double longitude) {
    }
}
