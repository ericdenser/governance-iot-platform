package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.audit.AuditService;
import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.enums.status.CommandStatus;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.CommandBatch;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.request.CommandRequest;
import com.eric.governanceApi.governanceApi.model.response.AgentBroadcastResultDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandBatchResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.repository.CommandBatchRepository;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.UserGroupAssignmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class CommandsService {

    private static final List<GroupRole> MEMBER_OR_OWNER = List.of(GroupRole.MEMBER, GroupRole.OWNER);

    private final DeviceRepository deviceRepository;
    private final AgentClient agentClient;
    private final FirmwareService firmwareService;
    private final AuditService auditService;
    private final UserGroupAssignmentRepository assignmentRepository;
    private final CommandBatchRepository commandBatchRepository;
    private final CommandRecordRepository commandRecordRepository;

    public CommandsService(DeviceRepository deviceRepository, AgentClient agentClient,
                           FirmwareService firmwareService, AuditService auditService,
                           UserGroupAssignmentRepository assignmentRepository,
                           CommandBatchRepository commandBatchRepository,
                           CommandRecordRepository commandRecordRepository) {
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
        this.firmwareService = firmwareService;
        this.auditService = auditService;
        this.assignmentRepository = assignmentRepository;
        this.commandBatchRepository = commandBatchRepository;
        this.commandRecordRepository = commandRecordRepository;
    }

    // UPDATE vai para FirmwareService que tem seu próprio @Auditable(FIRMWARE_DEPLOYED)
    // Simples (REBOOT, DEEP_SLEEP, ROLLBACK) auditamos manualmente para evitar duplo registro
    @Transactional
    public CommandResultResponseDTO execute(CommandRequest request) throws Exception {
        CommandRequest authorized = filterAuthorizedDevices(request);
        return switch (authorized.command()) {
            case UPDATE           -> firmwareService.deploy(
                                        authorized.params().get("versionId").toString(),
                                        authorized.targetDevices());
            case REBOOT, DEEP_SLEEP, FIRMWARE_ROLLBACK -> handleSimpleCommand(authorized);
        };
    }

    private CommandRequest filterAuthorizedDevices(CommandRequest request) {
        if (isAdmin()) return request;
        String actorId = currentActorId();
        if (actorId == null) throw new SecurityException("Não autenticado.");

        List<String> allowed = request.targetDevices().stream()
                .filter(devId -> assignmentRepository
                        .countByUserAndDeviceWithRoles(actorId, devId, MEMBER_OR_OWNER) > 0)
                .toList();

        if (allowed.isEmpty()) {
            throw new SecurityException("Sem permissão de MEMBER/OWNER para enviar comandos a nenhum dos devices solicitados.");
        }
        if (allowed.size() == request.targetDevices().size()) return request;
        return new CommandRequest(request.command(), allowed, request.params());
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private String currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) return jwt.getSubject();
        return null;
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
        List<String> notFound   = new ArrayList<>();
        Map<String, CommandRecord> pendingByDeviceId = new HashMap<>();

        String payloadStr = (request.params() != null && !request.params().isEmpty())
                ? request.params().toString() : null;

        CommandBatch batch = new CommandBatch();
        batch.setCommandType(request.command());
        batch.setPayload(payloadStr);
        commandBatchRepository.save(batch);

        for (String devId : request.targetDevices()) {
            deviceRepository.findByDeviceId(devId).ifPresentOrElse(device -> {
                if (device.getStatus() == DeviceStatus.COMMAND_PENDING) {
                    skipped.add(devId);
                    addSkippedRecord(device, batch, request, "Já existe um comando pendente em execução.");
                    log.warn("Device {} ignorado. Já existe um comando pendente em execução.", devId);
                    return;
                }
                if (device.getStatus() != DeviceStatus.ACTIVE) {
                    skipped.add(devId);
                    addSkippedRecord(device, batch, request, "Device não está ACTIVE (status=" + device.getStatus() + ").");
                    log.info("Device {} não está ACTIVE, skippando...", devId);
                    return;
                }

                CommandRecord record = new CommandRecord();
                record.setCommandType(request.command());
                record.setPayload(payloadStr);
                record.setBatch(batch);
                device.addCommandRecord(record);
                device.setStatus(DeviceStatus.COMMAND_PENDING);
                activeDevs.add(devId);
                pendingByDeviceId.put(devId, record);
                log.info("Device {} válido, status alterado para COMMAND_PENDING", devId);
                deviceRepository.save(device);

            }, () -> {
                skipped.add(devId);
                notFound.add(devId);
                log.info("Device {} não encontrado, skippando...", devId);
            });
        }

        if (!notFound.isEmpty()) {
            batch.setNotFoundIds(String.join(",", notFound));
            commandBatchRepository.save(batch);
        }

        CommandResultResponseDTO result;

        if (activeDevs.isEmpty()) {
            log.info("Nenhum device ativo");
            result = new CommandResultResponseDTO(request.command().name(), List.of(), List.of(), skipped);
        } else {
            Map<String, Object> payload = buildPayload(request.command(), request.params());
            AgentBroadcastResultDTO agentResult = agentClient.broadcastCommands(request.command(), payload, activeDevs);
            for (String devId : agentResult.failed()) {
                CommandRecord record = pendingByDeviceId.get(devId);
                if (record == null) continue;
                record.setStatus(CommandStatus.PUBLISH_FAILED);
                record.setCompletedAt(Instant.now());
                record.setErrorMessage("Falha ao publicar no broker MQTT (agent).");
                deviceRepository.findByDeviceId(devId).ifPresent(device -> {
                    device.setStatus(DeviceStatus.ACTIVE);
                    deviceRepository.save(device);
                });
                log.warn("Device {}: publish falhou no agent — record marcado PUBLISH_FAILED e device liberado.", devId);
            }
            result = new CommandResultResponseDTO(
                request.command().name(), agentResult.publishedTo(), agentResult.failed(), skipped);
        }

        if (actorId != null) {
            auditService.record(actorId, actorUsername, AuditAction.COMMAND_SENT,
                "COMMAND", request.command().name(), request.targetDevices().toString(), true, null);
        }

        return result;
    }

    private void addSkippedRecord(Device device, CommandBatch batch, CommandRequest request, String reason) {
        CommandRecord record = new CommandRecord();
        record.setCommandType(request.command());
        if (request.params() != null && !request.params().isEmpty()) {
            record.setPayload(request.params().toString());
        }
        record.setBatch(batch);
        record.setStatus(CommandStatus.SKIPPED);
        record.setCompletedAt(Instant.now());
        record.setErrorMessage(reason);
        device.addCommandRecord(record);
        deviceRepository.save(device);
    }

    // ─── Listagem de batches (histórico de comandos) ───────────────────────────

    @Transactional(readOnly = true)
    public Page<CommandBatchResponseDTO> listBatches(Pageable pageable) {
        Page<CommandBatch> page = isAdmin()
                ? commandBatchRepository.findAllByOrderBySentAtDesc(pageable)
                : commandBatchRepository.findAllByKeycloakUserId(currentActorId(), pageable);

        @SuppressWarnings("null")
        List<Long> batchIds = page.getContent().stream().map(CommandBatch::getId).toList();

        Map<Long, Map<CommandStatus, Long>> countsByBatch = new HashMap<>();
        if (!batchIds.isEmpty()) {
            for (CommandRecordRepository.BatchStatusCount row : commandRecordRepository.countStatusesByBatchIds(batchIds)) {
                countsByBatch.computeIfAbsent(row.getBatchId(), k -> new EnumMap<>(CommandStatus.class))
                             .put(row.getStatus(), row.getTotal());
            }
        }

        return page.map(b -> CommandBatchResponseDTO.from(b, countsByBatch.getOrDefault(b.getId(), Map.of())));
    }

    @Transactional(readOnly = true)
    public List<CommandRecordResponseDTO> getBatchRecords(String batchId) {
        commandBatchRepository.findByBatchId(batchId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.COMMAND_NOT_FOUND,
                        "Batch de comando " + batchId + " não encontrado."));

        List<CommandRecord> records = isAdmin()
                ? commandRecordRepository.findByBatch_BatchIdOrderByIdAsc(batchId)
                : commandRecordRepository.findByBatchIdVisibleTo(batchId, currentActorId());

        return records.stream().map(CommandRecordResponseDTO::from).toList();
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
