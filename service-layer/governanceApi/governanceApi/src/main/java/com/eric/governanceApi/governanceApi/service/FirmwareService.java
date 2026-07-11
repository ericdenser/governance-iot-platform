package com.eric.governanceApi.governanceApi.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.projection.DeployableVersionProjection;
import com.eric.governanceApi.governanceApi.model.projection.FirmwareListProjection;
import com.eric.governanceApi.governanceApi.model.request.CreateFirmwareRequestDTO;
import com.eric.governanceApi.governanceApi.model.request.SensorConfigDTO;
import com.eric.governanceApi.governanceApi.model.request.UploadVersionRequestDTO;
import com.eric.governanceApi.governanceApi.model.response.AgentBroadcastResultDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.FirmwareResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.FirmwareVersionResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.FirmwareVersionSummaryDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareVersionRepository;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;
import com.eric.governanceApi.governanceApi.repository.UserGroupAssignmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FirmwareService {

    private final FirmwareRepository firmwareRepository;
    private final FirmwareVersionRepository firmwareVersionRepository;
    private final DeviceRepository deviceRepository;
    private final AgentClient agentClient;
    private final SensorRepository sensorRepository;
    private final UserGroupAssignmentRepository assignmentRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    @Value("${ota.firmware-storage-path}")
    private String storagePath;

    @Value("${ota.public-base-url}")
    private String publicBaseUrl;

    @Value("${ota.max-firmware-size-mb:4}")
    private int maxSizeMb;

    public FirmwareService(FirmwareRepository firmwareRepository,
                           FirmwareVersionRepository firmwareVersionRepository,
                           DeviceRepository deviceRepository,
                           AgentClient agentClient,
                           SensorRepository sensorRepository,
                           UserGroupAssignmentRepository assignmentRepository) {
        this.firmwareRepository = firmwareRepository;
        this.firmwareVersionRepository = firmwareVersionRepository;
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
        this.sensorRepository = sensorRepository;
        this.assignmentRepository = assignmentRepository;
    }


    // ─── Write ─────────────────────────────────────────────────────────────────

    @Auditable(action = AuditAction.FIRMWARE_UPLOADED, targetType = "FIRMWARE")
    @Transactional
    public FirmwareResponseDTO createFirmware(MultipartFile file, CreateFirmwareRequestDTO requestDTO) throws Exception {

        String ownerGroupId = resolveOwnerGroupIdForCreate(requestDTO);

        if (firmwareRepository.existsByNameInScope(requestDTO.firmwareName(), ownerGroupId)) {
            throw new ConflictException(ErrorCode.FIRMWARE_NAME_DUPLICATE,
                "Já existe um firmware chamado '" + requestDTO.firmwareName() + "' neste escopo.");
        }

        validateBinary(file);
        String sha256 = computeSha256(file);
        String filename = persistBinary(file, requestDTO.initialVersion(), sha256);

        Firmware fw = new Firmware();
        fw.setFirmwareName(requestDTO.firmwareName());
        fw.setDescription(requestDTO.description());
        fw.setOwnerGroupId(ownerGroupId);
        fw.setProvisioningFirmware(requestDTO.isProvisioning());

        // 1ª versão não tem releaseNotes — quem descreve o firmware é a description do product.
        FirmwareVersion v = buildVersion(fw, requestDTO.initialVersion(),
                                         file.getOriginalFilename(), filename, sha256,
                                         file.getSize(), null,
                                         requestDTO.sensors());
        fw.getVersions().add(v);

        firmwareRepository.save(fw);  // cascade grava a versão junto

        log.info("Firmware '{}' criado — ownerGroupId: {} | primeira versão v{} | ID: {}",
                 requestDTO.firmwareName(), ownerGroupId, requestDTO.initialVersion(), fw.getFirmwareId());

        return FirmwareResponseDTO.from(fw, v);
    }

    @Auditable(action = AuditAction.FIRMWARE_UPLOADED, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public FirmwareVersionResponseDTO uploadNewVersion(String firmwareId, MultipartFile file, UploadVersionRequestDTO requestDTO) throws Exception {

        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                "Firmware " + firmwareId + " não encontrado."));

        requireFirmwareManagement(fw);

        // Firmware de provisioning: só ADMIN pode subir novas versões
        if (fw.isProvisioningFirmware() && !isAdmin()) {
            throw new SecurityException("Apenas administradores podem subir versões do firmware de provisionamento.");
        }

        if (firmwareVersionRepository.existsByFirmware_IdAndVersion(fw.getId(), requestDTO.version())) {
            throw new ConflictException(ErrorCode.FIRMWARE_VERSION_DUPLICATE,
                "Firmware '" + fw.getFirmwareName() + "' já possui versão v" + requestDTO.version() + ".");
        }

        validateBinary(file);
        String sha256 = computeSha256(file);
        String filename = persistBinary(file, requestDTO.version(), sha256);

        FirmwareVersion v = buildVersion(fw, requestDTO.version(),
                                         file.getOriginalFilename(), filename, sha256,
                                         file.getSize(), requestDTO.releaseNotes(),
                                         requestDTO.sensors());
        fw.getVersions().add(v);
        firmwareVersionRepository.save(v);

        log.info("Nova versão de '{}' registrada — v{} | ID: {}",
                 fw.getFirmwareName(), requestDTO.version(), v.getFirmwareVersionId());

        return FirmwareVersionResponseDTO.from(v);
    }

    @Auditable(action = AuditAction.FIRMWARE_DEPRECATED, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public FirmwareVersionResponseDTO deprecateVersion(String versionId) {
        FirmwareVersion v = firmwareVersionRepository.findByFirmwareVersionId(versionId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_VERSION_NOT_FOUND,
                "Versão " + versionId + " não encontrada."));

        requireFirmwareManagement(v.getFirmware());

        if (v.getStatus() == FirmwareStatus.DEPRECATED) {
            return FirmwareVersionResponseDTO.from(v);
        }

        v.setStatus(FirmwareStatus.DEPRECATED);
        firmwareVersionRepository.save(v);

        log.info("Versão v{} de '{}' marcada como DEPRECATED",
                 v.getVersion(), v.getFirmware().getFirmwareName());

        return FirmwareVersionResponseDTO.from(v);
    }

    @Auditable(action = AuditAction.FIRMWARE_DEPLOYED, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public CommandResultResponseDTO deploy(String versionId, List<String> targetDevices) throws Exception {

        FirmwareVersion v = firmwareVersionRepository.findByFirmwareVersionId(versionId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_VERSION_NOT_FOUND,
                "Versão " + versionId + " não encontrada."));

        requireFirmwareAccess(v.getFirmware());

        if (v.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Versão v" + v.getVersion() + " está DEPRECATED e não pode ser deployada.");
        }

        Map<String, Object> payload = Map.of(
            "version", v.getVersion(),
            "url",     v.getDownloadUrl()
        );

        List<String> activeDevs = new ArrayList<>();
        List<String> skipped    = new ArrayList<>();

        String payloadJson = MAPPER.writeValueAsString(payload);

        for (String devId : targetDevices) {
            deviceRepository.findByDeviceId(devId).ifPresentOrElse(
                device -> {
                    if (device.getStatus() == DeviceStatus.COMMAND_PENDING) {
                        skipped.add(devId);
                        log.warn("Device {} ignorado. Já existe um comando pendente em execução.", devId);
                        return;
                    }
                    if (device.getStatus() != DeviceStatus.ACTIVE) {
                        skipped.add(devId);
                        log.info("Device de ID {} não está ACTIVE, skippando...", devId);
                        return;
                    }
                    FirmwareVersion currentVer = device.getFirmwareVersion();
                    if (currentVer != null && currentVer.getFirmwareVersionId().equals(v.getFirmwareVersionId())) {
                        skipped.add(devId);
                        log.warn("Device {} ignorado. Já está na versão v{}", devId, v.getVersion());
                        return;
                    }
                    CommandRecord record = new CommandRecord();
                    record.setCommandType(DeviceCommands.UPDATE);
                    record.setPayload(payloadJson);
                    record.setTargetVersionId(v.getFirmwareVersionId());
                    device.addCommandRecord(record);
                    device.setAttemptedFirmwareVersion(v);  // marca OTA em andamento
                    device.setStatus(DeviceStatus.COMMAND_PENDING);
                    activeDevs.add(devId);
                    log.info("Device de ID {} válido, status alterado para COMMAND_PENDING", devId);
                    deviceRepository.save(device);
                }, () -> {
                    skipped.add(devId);
                    log.info("Device de ID {} não encontrado, skippando...", devId);
                });
        }

        if (activeDevs.isEmpty()) {
            return new CommandResultResponseDTO(
                DeviceCommands.UPDATE.toString(),
                List.of(),
                List.of(),
                skipped
            );
        }

        AgentBroadcastResultDTO agentResult = agentClient.broadcastCommands(DeviceCommands.UPDATE, payload, activeDevs);

        return new CommandResultResponseDTO(
            DeviceCommands.UPDATE.toString(),
            agentResult.publishedTo(),
            agentResult.failed(),
            skipped);
    }

    @Auditable(action = AuditAction.FIRMWARE_SET_PROVISIONING, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public FirmwareResponseDTO setProvisioningFirmware(String firmwareId) {
        if (!isAdmin()) {
            throw new SecurityException("Apenas administradores podem definir o firmware de provisionamento.");
        }

        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                "Firmware " + firmwareId + " não encontrado."));

        if (fw.getOwnerGroupId() != null) {
            throw new IllegalArgumentException(
                "Apenas firmwares de plataforma podem ser marcados como provisionamento.");
        }

        FirmwareVersion latest = firmwareVersionRepository
            .findFirstByFirmware_FirmwareIdOrderByUploadedAtDesc(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_VERSION_NOT_FOUND,
                "Firmware " + firmwareId + " não tem versão registrada para ser provisionamento."));

        if (latest.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Versão mais recente (v" + latest.getVersion() + ") está DEPRECATED — não pode ser provisionamento.");
        }

        firmwareRepository.findByProvisioningFirmwareTrue().ifPresent(current -> {
            if (!current.getFirmwareId().equals(fw.getFirmwareId())) {
                current.setProvisioningFirmware(false);
                firmwareRepository.save(current);
            }
        });

        fw.setProvisioningFirmware(true);
        firmwareRepository.save(fw);

        log.info("Firmware '{}' definido como provisionamento (versão em uso: v{}).",
                 fw.getFirmwareName(), latest.getVersion());

        return FirmwareResponseDTO.from(fw, latest);
    }


    // ─── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FirmwareResponseDTO getByFirmwareId(String firmwareId) {
        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                "Firmware " + firmwareId + " não encontrado."));

        requireFirmwareAccess(fw);

        FirmwareVersion latest = firmwareVersionRepository
            .findFirstByFirmware_FirmwareIdOrderByUploadedAtDesc(firmwareId)
            .orElse(null);

        return FirmwareResponseDTO.from(fw, latest);
    }

    @Transactional(readOnly = true)
    public FirmwareVersionResponseDTO getByVersionId(String versionId) {
        FirmwareVersion v = firmwareVersionRepository.findByFirmwareVersionId(versionId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_VERSION_NOT_FOUND,
                "Versão " + versionId + " não encontrada."));

        requireFirmwareAccess(v.getFirmware());
        return FirmwareVersionResponseDTO.from(v);
    }

    // Usado pelo FlashPackageService: versão mais recente do product de provisioning. 
    @Transactional(readOnly = true)
    public FirmwareVersion getProvisioningVersion() {
        return firmwareVersionRepository.findFirstByFirmware_ProvisioningFirmwareTrueOrderByUploadedAtDesc()
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                    "Nenhum firmware de provisioning disponível."));
    }

    @Transactional(readOnly = true)
    public List<FirmwareResponseDTO> listFirmware() {
        List<FirmwareListProjection> rows = isAdmin()
            ? firmwareRepository.findAllRowsAdmin()
            : firmwareRepository.findVisibleRowsByGroup(currentUserGroupIds());

        return rows.stream()
            .map(row -> new FirmwareResponseDTO(
                row.firmwareId(),
                row.firmwareName(),
                row.description(),
                row.ownerGroupId(),
                row.provisioningFirmware(),
                row.createdAt(),
                row.createdByActorId(),
                row.createdByUsername(),
                row.versionsCount().intValue(),
                row.latestVersionId() == null ? null : new FirmwareVersionSummaryDTO(
                    row.latestVersionId(),
                    row.latestVersionString(),
                    row.latestStatus(),
                    row.latestUploadedAt(),
                    row.latestCreatedByUsername(),
                    row.latestDeployCount() != null ? row.latestDeployCount() : 0,
                    row.latestSizeBytes() != null ? row.latestSizeBytes() : 0L
                )
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<FirmwareVersionSummaryDTO> listVersions(String firmwareId) {
        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                "Firmware " + firmwareId + " não encontrado."));

        requireFirmwareAccess(fw);

        return firmwareVersionRepository
            .findByFirmware_FirmwareIdOrderByUploadedAtDesc(firmwareId)
            .stream()
            .map(FirmwareVersionSummaryDTO::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<DeployableVersionProjection> listDeployable() {
        return isAdmin()
            ? firmwareVersionRepository.findAllDeployableAdmin()
            : firmwareVersionRepository.findDeployableByGroups(currentUserGroupIds());
    }


    // ─── Auth helpers ──────────────────────────────────────────────────────────

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private String currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }

    private List<String> currentUserGroupIds() {
        String uid = currentActorId();
        if (uid == null) return List.of();
        return assignmentRepository.findGroupUuidsByKeycloakUserId(uid);
    }

    private List<String> currentUserManagementGroupIds() {
        String uid = currentActorId();
        if (uid == null) return List.of();
        return assignmentRepository.findGroupUuidsByKeycloakUserIdAndRoles(
            uid, List.of(GroupRole.MEMBER, GroupRole.OWNER));
    }

    private void requireFirmwareAccess(Firmware fw) {
        if (isAdmin()) return;
        if (fw.getOwnerGroupId() == null) return; // firmware de plataforma é visível a todos
        List<String> groupIds = currentUserGroupIds();
        if (!groupIds.contains(fw.getOwnerGroupId())) {
            throw new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND,
                "Firmware " + fw.getFirmwareId() + " não encontrado.");
        }
    }

    private void requireFirmwareManagement(Firmware fw) {
        if (isAdmin()) return;
        if (fw.getOwnerGroupId() == null) {
            throw new SecurityException("Apenas administradores podem gerenciar firmware de plataforma.");
        }
        List<String> managementGroupIds = currentUserManagementGroupIds();
        if (!managementGroupIds.contains(fw.getOwnerGroupId())) {
            throw new SecurityException("Você precisa ser MEMBER ou OWNER do grupo dono deste firmware.");
        }
    }

    private String resolveOwnerGroupIdForCreate(CreateFirmwareRequestDTO requestDTO) {
        if (requestDTO.isProvisioning()) {
            if (!isAdmin()) {
                throw new SecurityException("Apenas administradores podem criar firmware de provisionamento.");
            }
            if (firmwareRepository.findByProvisioningFirmwareTrue().isPresent()) {
                throw new ConflictException(ErrorCode.CONFLICT,
                    "Já existe firmware de provisionamento registrado.");
            }
            return null;
        }
        if (isAdmin()) {
            return requestDTO.ownerGroupId();
        }
        String ownerGroupId = requestDTO.ownerGroupId();
        if (ownerGroupId == null || ownerGroupId.isBlank()) {
            throw new IllegalArgumentException("Informe o grupo ao qual o firmware pertence.");
        }
        String actorId = currentActorId();
        List<String> managementGroupIds = assignmentRepository.findGroupUuidsByKeycloakUserIdAndRoles(
            actorId, List.of(GroupRole.MEMBER, GroupRole.OWNER));
        if (!managementGroupIds.contains(ownerGroupId)) {
            throw new SecurityException("Você não é MEMBER ou OWNER do grupo informado.");
        }
        return ownerGroupId;
    }


    // ─── Binary handling ───────────────────────────────────────────────────────

    private String persistBinary(MultipartFile file, String version, String sha256) {
        String filename = "firmware_v" + version + "_" + sha256.substring(0, 12) + ".bin";
        Path dest = Paths.get(storagePath, filename);
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InfrastructureException(ErrorCode.STORAGE_UNAVAILABLE,
                "Erro ao salvar firmware no disco: " + e.getMessage());
        }
        dest.toFile().setReadable(true, true);
        dest.toFile().setWritable(false);
        return filename;
    }

    private FirmwareVersion buildVersion(Firmware fw, String version, String originalFilename,
                                         String filename, String sha256, long sizeBytes,
                                         String releaseNotes, List<SensorConfigDTO> sensors) {
        FirmwareVersion v = new FirmwareVersion();
        v.setFirmware(fw);
        v.setVersion(version);
        v.setFilename(filename);
        v.setOriginalFilename(originalFilename);
        v.setSha256(sha256);
        v.setSizeBytes(sizeBytes);
        v.setDownloadUrl(publicBaseUrl + "/" + filename);
        v.setReleaseNotes(releaseNotes);
        v.setStatus(FirmwareStatus.STAGED);
        attachSensors(v, sensors);
        return v;
    }

    private void attachSensors(FirmwareVersion v, List<SensorConfigDTO> sensors) {
        if (sensors == null || sensors.isEmpty()) return;
        for (SensorConfigDTO sensorDto : sensors) {
            sensorRepository.findBySensorId(sensorDto.sensorId()).ifPresent(sensor -> {
                FirmwareSensorConfig cfg = new FirmwareSensorConfig();
                cfg.setFirmwareVersion(v);
                cfg.setPin(sensorDto.pin());
                cfg.setSensor(sensor);
                v.getSensorConfigs().add(cfg);
            });
        }
    }

    private void validateBinary(MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            log.warn("Arquivo vazio.");
            throw new IllegalArgumentException("Arquivo vazio.");
        }

        String name = file.getOriginalFilename();

        if (name == null || !name.toLowerCase().endsWith(".bin")) {
            log.warn("Apenas .bin aceito. Recebido: {}", name);
            throw new IllegalArgumentException("Apenas .bin aceito. Recebido: " + name);
        }

        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            log.warn("Excede {}MB. Recebido: {} bytes.", maxSizeMb, file.getSize());
            throw new IllegalArgumentException(String.format(
                "Excede %dMB. Recebido: %d bytes.", maxSizeMb, file.getSize()));
        }

        if (file.getSize() < 30_000) {
            log.warn("Muito pequeno ({} bytes). Mínimo: ~30KB.", file.getSize());
            throw new IllegalArgumentException(String.format(
                "Muito pequeno (%d bytes). Mínimo: ~30KB.", file.getSize()));
        }

        byte[] header = new byte[16];
        try (InputStream is = file.getInputStream()) {
            if (is.read(header) < 16) {
                log.warn("Arquivo curto demais.");
                throw new IllegalArgumentException("Arquivo curto demais.");
            }
        }
        if ((header[0] & 0xFF) != 0xE9) {
            log.warn("Magic byte 0x{} inválido. Esperado: 0xE9.", String.format("%02X", header[0] & 0xFF));
            throw new IllegalArgumentException(String.format(
                "Magic byte 0x%02X inválido. Esperado: 0xE9.", header[0] & 0xFF));
        }
    }

    private String computeSha256(MultipartFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = file.getInputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) digest.update(buf, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
