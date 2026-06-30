package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.audit.AuditService;
import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.request.CommandRequest;
import com.eric.governanceApi.governanceApi.model.response.AgentBroadcastResultDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
public class CommandsService {

    private final DeviceRepository deviceRepository;
    private final AgentClient agentClient;
    private final FirmwareService firmwareService;
    private final AuditService auditService;

    public CommandsService(DeviceRepository deviceRepository, AgentClient agentClient,
                           FirmwareService firmwareService, AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
        this.firmwareService = firmwareService;
        this.auditService = auditService;
    }

    // UPDATE vai para FirmwareService que tem seu próprio @Auditable(FIRMWARE_DEPLOYED)
    // Simples (REBOOT, DEEP_SLEEP, ROLLBACK) auditamos manualmente para evitar duplo registro
    @Transactional
    public CommandResultResponseDTO execute(CommandRequest request) throws Exception {
        return switch (request.command()) {
            case UPDATE           -> firmwareService.deploy(
                                        request.params().get("firmwareId").toString(),
                                        request.targetDevices());
            case REBOOT, DEEP_SLEEP, FIRMWARE_ROLLBACK -> handleSimpleCommand(request);
        };
    }

    private CommandResultResponseDTO handleSimpleCommand(CommandRequest request) {
        String actorId = null;
        String actorUsername = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            actorId = jwt.getSubject();
            actorUsername = jwt.getClaimAsString("preferred_username");
        }

        log.info("Verificando situação dos devices destinatários.");

        List<String> activeDevs = new ArrayList<>();
        List<String> skipped    = new ArrayList<>();

        for (String devId : request.targetDevices()) {
            deviceRepository.findByDeviceId(devId).ifPresentOrElse(device -> {
                if (device.getStatus() == DeviceStatus.COMMAND_PENDING) {
                    skipped.add(devId);
                    log.warn("Device {} ignorado. Já existe um comando pendente em execução.", devId);
                    return;
                }
                if (device.getStatus() != DeviceStatus.ACTIVE) {
                    skipped.add(devId);
                    log.info("Device {} não está ACTIVE, skippando...", devId);
                    return;
                }

                CommandRecord record = new CommandRecord();
                record.setCommandType(request.command());
                if (request.params() != null && !request.params().isEmpty()) {
                    record.setPayload(request.params().toString());
                }
                device.addCommandRecord(record);
                device.setStatus(DeviceStatus.COMMAND_PENDING);
                activeDevs.add(devId);
                log.info("Device {} válido, status alterado para COMMAND_PENDING", devId);
                deviceRepository.save(device);

            }, () -> {
                skipped.add(devId);
                log.info("Device {} não encontrado, skippando...", devId);
            });
        }

        CommandResultResponseDTO result;

        if (activeDevs.isEmpty()) {
            log.info("Nenhum device ativo");
            result = new CommandResultResponseDTO(request.command().name(), List.of(), List.of(), skipped);
        } else {
            Map<String, Object> payload = buildPayload(request.command(), request.params());
            AgentBroadcastResultDTO agentResult = agentClient.broadcastCommands(request.command(), payload, activeDevs);
            result = new CommandResultResponseDTO(
                request.command().name(), agentResult.publishedTo(), agentResult.failed(), skipped);
        }

        if (actorId != null) {
            auditService.record(actorId, actorUsername, AuditAction.COMMAND_SENT,
                "COMMAND", request.command().name(), request.targetDevices().toString(), true, null);
        }

        return result;
    }

    private Map<String, Object> buildPayload(DeviceCommands command, Map<String, Object> params) {
        Map<String, Object> payload = new HashMap<>();
        switch (command) {
            case REBOOT -> {
                int delayMs = 3000;
                if (params != null && params.containsKey("delay_ms")) {
                    delayMs = ((Number) params.get("delay_ms")).intValue();
                }
                payload.put("delay_ms", delayMs);
            }
            case DEEP_SLEEP -> {
                if (params == null || !params.containsKey("duration_s")) {
                    throw new IllegalArgumentException("DEEP_SLEEP exige 'duration_s' em params.");
                }
                int durationS = ((Number) params.get("duration_s")).intValue();
                if (durationS < 10 || durationS > 259200) {
                    throw new IllegalArgumentException("duration_s deve estar entre 10 e 259200.");
                }
                payload.put("duration_s", durationS);
            }
            default -> {}
        }
        return payload;
    }
}
