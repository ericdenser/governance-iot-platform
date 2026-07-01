package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.config.AgentClient;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.DeviceCommands;
import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.InfrastructureException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.CommandRecord;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareSensorConfig;
import com.eric.governanceApi.governanceApi.model.request.FirmwareUploadMetadataDTO;
import com.eric.governanceApi.governanceApi.model.request.SensorConfigDTO;
import com.eric.governanceApi.governanceApi.model.response.AgentBroadcastResultDTO;
import com.eric.governanceApi.governanceApi.model.response.CommandResultResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.FirmwareResponseDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
import com.eric.governanceApi.governanceApi.repository.SensorRepository;
import com.eric.governanceApi.governanceApi.repository.UserGroupAssignmentRepository;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
public class FirmwareService {

    private final FirmwareRepository firmwareRepository;
    private final DeviceRepository deviceRepository;
    private final AgentClient agentClient;
    private final SensorRepository sensorRepository;
    private final UserGroupAssignmentRepository assignmentRepository;

    @Value("${ota.firmware-storage-path}")
    private String storagePath;

    @Value("${ota.public-base-url}")
    private String publicBaseUrl;

    @Value("${ota.max-firmware-size-mb:4}")
    private int maxSizeMb;

    public FirmwareService(FirmwareRepository firmwareRepository,
                           DeviceRepository deviceRepository,
                           AgentClient agentClient,
                           SensorRepository sensorRepository,
                           UserGroupAssignmentRepository assignmentRepository) {
        this.firmwareRepository = firmwareRepository;
        this.deviceRepository = deviceRepository;
        this.agentClient = agentClient;
        this.sensorRepository = sensorRepository;
        this.assignmentRepository = assignmentRepository;
    }


    @Auditable(action = AuditAction.FIRMWARE_UPLOADED, targetType = "FIRMWARE")
    @Transactional
    public FirmwareResponseDTO upload(MultipartFile file, FirmwareUploadMetadataDTO metadataDTO) throws Exception {

        String ownerGroupId;

        if (metadataDTO.isProvisioning()) {
            // Provisioning firmware is always platform-level — ADMIN only
            if (!isAdmin()) {
                throw new SecurityException("Apenas administradores podem fazer upload de firmware de provisionamento.");
            }
            if (firmwareRepository.findByProvisioningFirmwareTrue().isPresent()) {
                throw new ConflictException("Provisioning firmware already exists");
            }
            ownerGroupId = null;
        } else if (isAdmin()) {
            // Admin can create platform firmware (ownerGroupId null) or assign to any group
            ownerGroupId = metadataDTO.ownerGroupId();
        } else {
            // ROLE_USER must specify a group they are MEMBER or OWNER of
            ownerGroupId = metadataDTO.ownerGroupId();
            if (ownerGroupId == null || ownerGroupId.isBlank()) {
                throw new IllegalArgumentException("Informe o grupo ao qual o firmware pertence.");
            }
            String actorId = currentActorId();
            List<String> managementGroupIds = assignmentRepository.findGroupUuidsByKeycloakUserIdAndRoles(
                actorId, List.of(GroupRole.MEMBER, GroupRole.OWNER));
            if (!managementGroupIds.contains(ownerGroupId)) {
                throw new SecurityException("Você não é MEMBER ou OWNER do grupo informado.");
            }
        }

        // Version unique per ownerGroupId scope (null-safe JPQL query)
        if (firmwareRepository.existsByVersionInScope(metadataDTO.version(), ownerGroupId)) {
            throw new ConflictException("Versão " + metadataDTO.version() + " já existe neste escopo.");
        }

        validateBinary(file);

        String sha256  = computeSha256(file);
        String filename = "firmware_v" + metadataDTO.version() + "_" + sha256.substring(0, 12) + ".bin";
        Path dest       = Paths.get(storagePath, filename);

        try {
            Files.createDirectories(dest.getParent());
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InfrastructureException("Erro ao salvar firmware no disco: " + e.getMessage());
        }

        dest.toFile().setReadable(true, true);
        dest.toFile().setWritable(false);

        Firmware fw = new Firmware();
        fw.setVersion(metadataDTO.version());
        fw.setOwnerGroupId(ownerGroupId);
        fw.setFilename(filename);
        fw.setOriginalFilename(file.getOriginalFilename());
        fw.setSha256(sha256);
        fw.setSizeBytes(file.getSize());
        fw.setDownloadUrl(publicBaseUrl + "/" + filename);
        fw.setReleaseNotes(metadataDTO.releaseNotes());
        fw.setProvisioningFirmware(metadataDTO.isProvisioning());
        fw.setStatus(FirmwareStatus.STAGED);

        if (metadataDTO.sensors() != null && !metadataDTO.sensors().isEmpty()) {
            for (SensorConfigDTO sensorDto : metadataDTO.sensors()) {
                sensorRepository.findBySensorId(sensorDto.sensorId()).ifPresent(sensor -> {
                    FirmwareSensorConfig cfg = new FirmwareSensorConfig();
                    cfg.setFirmware(fw);
                    cfg.setPin(sensorDto.pin());
                    cfg.setSensor(sensor);
                    fw.getSensorConfigs().add(cfg);
                });
            }
        }

        firmwareRepository.save(fw);

        log.info("Firmware v{} registrado — ownerGroupId: {} | SHA256: {} | {} bytes | ID: {}",
                 metadataDTO.version(), ownerGroupId, sha256, file.getSize(), fw.getId());

        return FirmwareResponseDTO.from(fw);
    }

    @Auditable(action = AuditAction.FIRMWARE_DEPLOYED, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public CommandResultResponseDTO deploy(String firmwareId, List<String> targetDevices) throws Exception {

        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException("Firmware " + firmwareId + " não encontrado."));

        requireFirmwareAccess(fw);

        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Firmware v" + fw.getVersion() + " está DEPRECATED e não pode ser deployado.");
        }

        Map<String, Object> payload = Map.of(
            "version", fw.getVersion(),
            "url",     fw.getDownloadUrl()
        );

        List<String> activeDevs = new ArrayList<>();
        List<String> skipped    = new ArrayList<>();

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
                    if (device.getFirmware().getVersion() == fw.getVersion()) {
                        skipped.add(devId);
                        log.warn("Device {} ignorado. Já está na versão {}", devId, fw.getVersion());
                        return;
                    }
                    CommandRecord record = new CommandRecord();
                    record.setCommandType(DeviceCommands.UPDATE);
                    record.setPayload(payload.toString());
                    record.setTargetFirmwareId(fw.getFirmwareId());
                    device.addCommandRecord(record);
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

    @Auditable(action = AuditAction.FIRMWARE_DEPRECATED, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public FirmwareResponseDTO deprecate(String firmwareId) {
        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
            .orElseThrow(() -> new ResourceNotFoundException("Firmware " + firmwareId + " não encontrado."));

        requireFirmwareManagement(fw);

        fw.setStatus(FirmwareStatus.DEPRECATED);
        firmwareRepository.save(fw);

        log.info("Firmware v{} ({}) marcado como DEPRECATED", fw.getVersion(), firmwareId);

        return FirmwareResponseDTO.from(fw);
    }

    @Transactional(readOnly = true)
    public FirmwareResponseDTO getByFirmwareId(String firmwareId) {
        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
                .orElseThrow(() -> new ResourceNotFoundException("Firmware " + firmwareId + " não encontrado."));
        requireFirmwareAccess(fw);
        return FirmwareResponseDTO.from(fw);
    }

    @Transactional(readOnly = true)
    public List<FirmwareResponseDTO> listAll() {
        return visibleFirmware().stream()
                .map(FirmwareResponseDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public List<FirmwareResponseDTO> listDeployable() {
        return visibleFirmware().stream()
                .filter(f -> f.getStatus() != FirmwareStatus.DEPRECATED)
                .map(FirmwareResponseDTO::from).toList();
    }

    @Auditable(action = AuditAction.FIRMWARE_SET_PROVISIONING, targetType = "FIRMWARE", targetIdArg = 0)
    @Transactional
    public FirmwareResponseDTO setProvisioningFirmware(String firmwareId) {
        if (!isAdmin()) {
            throw new SecurityException("Apenas administradores podem definir o firmware de provisionamento.");
        }

        firmwareRepository.findByProvisioningFirmwareTrue().ifPresent(current -> {
            current.setProvisioningFirmware(false);
            firmwareRepository.save(current);
        });

        Firmware fw = firmwareRepository.findByFirmwareId(firmwareId)
                    .orElseThrow(() ->
                        new ResourceNotFoundException("Firmware " + firmwareId + " não encontrado."));

        if (fw.getStatus() == FirmwareStatus.DEPRECATED) {
            throw new IllegalArgumentException(
                "Firmware v" + fw.getVersion() + "está DEPRECATED e não pode ser provisioning.");
        }

        if (fw.getDeployCount() > 0) {
            throw new IllegalArgumentException(
                 "Firmware v" + fw.getVersion() + "está deployed em dispositivos, não pode ser provisioning.");
        }

        // Provisioning firmware is always platform-level
        fw.setOwnerGroupId(null);
        fw.setProvisioningFirmware(true);
        firmwareRepository.save(fw);
        return FirmwareResponseDTO.from(fw);
    }


    // ---- Auth helpers ----

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

    /** Groups the current user belongs to (any role) — for read access. */
    private List<String> currentUserGroupIds() {
        String uid = currentActorId();
        if (uid == null) return List.of();
        return assignmentRepository.findGroupUuidsByKeycloakUserId(uid);
    }

    /** Groups the current user can manage firmware in (MEMBER or OWNER). */
    private List<String> currentUserManagementGroupIds() {
        String uid = currentActorId();
        if (uid == null) return List.of();
        return assignmentRepository.findGroupUuidsByKeycloakUserIdAndRoles(
            uid, List.of(GroupRole.MEMBER, GroupRole.OWNER));
    }

    private List<Firmware> visibleFirmware() {
        if (isAdmin()) return firmwareRepository.findAllByOrderByVersionDesc();
        List<String> groupIds = currentUserGroupIds();
        if (groupIds.isEmpty()) return firmwareRepository.findByOwnerGroupIdIsNull();
        return firmwareRepository.findVisibleForUser(groupIds);
    }

    private void requireFirmwareAccess(Firmware fw) {
        if (isAdmin()) return;
        if (fw.getOwnerGroupId() == null) return; // platform firmware is readable by all
        List<String> groupIds = currentUserGroupIds();
        if (!groupIds.contains(fw.getOwnerGroupId())) {
            throw new ResourceNotFoundException("Firmware " + fw.getFirmwareId() + " não encontrado.");
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


    // ---- Binary validation ----

    private void validateBinary(MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            log.warn("Arquivo vazio.");
            throw new IllegalArgumentException("Arquivo vazio.");
        }

        String name = file.getOriginalFilename();

        if (name == null || !name.toLowerCase().endsWith(".bin")) {
            log.warn("Apenas .bin aceito. Recebido: " + name);
            throw new IllegalArgumentException("Apenas .bin aceito. Recebido: " + name);
        }

        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            log.warn("Excede %dMB. Recebido: %d bytes.", maxSizeMb, file.getSize());
            throw new IllegalArgumentException(String.format(
                "Excede %dMB. Recebido: %d bytes.", maxSizeMb, file.getSize()));
        }

        if (file.getSize() < 30_000) {
            log.warn("Muito pequeno (%d bytes). Mínimo: ~30KB.", file.getSize());
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
            log.warn("Magic byte 0x%02X inválido. Esperado: 0xE9.", header[0] & 0xFF);
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
