package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.projection.DeviceSummaryProjection;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceCertificateResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceDetailDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceMapPositionDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceSummaryDTO;
import com.eric.governanceApi.governanceApi.model.response.ErrorRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.EventRegistryResponseDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupMembershipRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import com.eric.governanceApi.governanceApi.service.HotStateService.LiveState;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final CommandRecordRepository commandRecordRepository;
    private final ErrorRecordRepository errorRecordRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final DeviceGroupMembershipRepository membershipRepository;
    private final HotStateService hotStateService;
    public DeviceService(DeviceRepository deviceRepository,
                         CommandRecordRepository commandRecordRepository,
                         ErrorRecordRepository errorRecordRepository,
                         EventRegistryRepository eventRegistryRepository,
                         DeviceGroupMembershipRepository membershipRepository, HotStateService hotStateService) {
        this.deviceRepository = deviceRepository;
        this.commandRecordRepository = commandRecordRepository;
        this.errorRecordRepository = errorRecordRepository;
        this.eventRegistryRepository = eventRegistryRepository;
        this.membershipRepository = membershipRepository;
        this.hotStateService = hotStateService;
    }
    
    @Transactional(readOnly = true)
    public Page<DeviceSummaryDTO> listAll(Pageable pageable, String search, DeviceStatus status) {
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();

        Page<DeviceSummaryProjection> rows;
        if (isAdmin()) {
            rows = deviceRepository.findAllSummaries(normalizedSearch, status, pageable);
        } else {
            String actorId = currentActorId();
            if (actorId == null) return Page.empty(pageable);
            rows = deviceRepository.findSummariesByUserGroups(actorId, normalizedSearch, status, pageable);
        }

        return rows.map(row -> new DeviceSummaryDTO(
            row.deviceId(),
            row.name(),
            row.status(),
            row.macAddress(),
            row.firmwareId(),
            row.firmwareName(),
            row.firmwareVersionId(),
            row.version(),
            row.createdAt(),
            row.lastSeen(),
            row.issuedByActorId(),
            row.issuedByUsername()
        ));
    }

    /**
     * Payload minimal pro mapa em tempo real. Retorna só devices com coord válida
     * (lat e lon presentes no Hash). RBAC igual ao listAll.
     *
     * Sem paginação — mapa consome tudo do escopo. Se o escopo do actor tiver
     * ~10k devices, ainda é 1 pipeline Redis + 1 query Postgres.
     */
    @Transactional(readOnly = true)
    public java.util.List<DeviceMapPositionDTO> listMapPositions() {
        java.util.List<String> deviceIds;
        if (isAdmin()) {
            deviceIds = deviceRepository.findAllDeviceIds();
        } else {
            String actorId = currentActorId();
            if (actorId == null) return java.util.List.of();
            deviceIds = deviceRepository.findDeviceIdsByUserGroups(actorId);
        }
        if (deviceIds.isEmpty()) return java.util.List.of();

        java.util.Map<String, LiveState> live = hotStateService.getLiveBulk(deviceIds);

        return live.entrySet().stream()
                .filter(e -> e.getValue().latitude() != null && e.getValue().longitude() != null)
                .map(e -> new DeviceMapPositionDTO(
                        e.getKey(),
                        e.getValue().latitude(),
                        e.getValue().longitude(),
                        e.getValue().lastSeen()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceDetailDTO getDevice(String deviceId) {
        // 1 query só (device + firmwareVersion + firmware) via @EntityGraph
        Device device = deviceRepository.findWithFirmwareByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado."));

        if (!isAdmin() && !canAccessDevice(device)) {
            // 404 pra não vazar existência do device pra quem não tem acesso
            throw new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado.");
        }

        LiveState live = hotStateService.getLive(deviceId);

        return DeviceDetailDTO.from(device, live);
    }

    @Transactional(readOnly = true)
    public Page<CommandRecordResponseDTO> getCommands(String deviceId, Pageable pageable) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado."));
        if (!isAdmin() && !canAccessDevice(device)) {
            throw new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado.");
        }
        return commandRecordRepository.findByTargetDevice_DeviceId(deviceId, pageable)
                .map(CommandRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<ErrorRecordResponseDTO> getErrors(String deviceId, Pageable pageable) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado."));
        if (!isAdmin() && !canAccessDevice(device)) {
            throw new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado.");
        }
        return errorRecordRepository.findByDevice_DeviceId(deviceId, pageable)
                .map(ErrorRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<EventRegistryResponseDTO> getEvents(String deviceId, Pageable pageable) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado."));
        if (!isAdmin() && !canAccessDevice(device)) {
            throw new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado.");
        }
        return eventRegistryRepository.findByDevice_DeviceIdOrderByOcurredAtDesc(deviceId, pageable)
                .map(EventRegistryResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public DeviceCertificateResponseDTO getCertificate(String deviceId) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND, "Device " + deviceId + " não encontrado."));
        if (device.getCertificate() == null) {
            throw new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND,
                "Device " + deviceId + " não possui certificado.");
        }
        return DeviceCertificateResponseDTO.from(device.getCertificate());
    }

    @Transactional(readOnly = true)
    public Page<EventRegistryResponseDTO> listAllEvents(Pageable pageable) {
        if (isAdmin()) {
            return eventRegistryRepository.findAllByOrderByOcurredAtDesc(pageable)
                    .map(EventRegistryResponseDTO::from);
        }
        String actorId = currentActorId();
        if (actorId == null) return Page.empty(pageable);
        return eventRegistryRepository.findAllByKeycloakUserId(actorId, pageable)
                .map(EventRegistryResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<CommandRecordResponseDTO> listAllCommands(Pageable pageable) {
        if (isAdmin()) {
            return commandRecordRepository.findAllByOrderBySentAtDesc(pageable)
                    .map(CommandRecordResponseDTO::from);
        }
        String actorId = currentActorId();
        if (actorId == null) return Page.empty(pageable);
        return commandRecordRepository.findAllByKeycloakUserId(actorId, pageable)
                .map(CommandRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<ErrorRecordResponseDTO> listAllErrors(Pageable pageable) {
        if (isAdmin()) {
            return errorRecordRepository.findAllByOrderByReportedAtDesc(pageable)
                    .map(ErrorRecordResponseDTO::from);
        }
        String actorId = currentActorId();
        if (actorId == null) return Page.empty(pageable);
        return errorRecordRepository.findAllByKeycloakUserId(actorId, pageable)
                .map(ErrorRecordResponseDTO::from);
    }

    // ── Access helpers ────────────────────────────────────────────────────────

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

    private boolean canAccessDevice(Device device) {
        String actorId = currentActorId();
        if (actorId == null) return false;
        return membershipRepository.countAccessibleByDeviceAndUser(device.getDeviceId(), actorId) > 0;
    }
}
