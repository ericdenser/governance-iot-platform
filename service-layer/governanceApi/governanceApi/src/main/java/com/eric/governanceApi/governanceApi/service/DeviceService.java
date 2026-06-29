package com.eric.governanceApi.governanceApi.service;

import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.response.CommandRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceCertificateResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceDetailDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceSummaryDTO;
import com.eric.governanceApi.governanceApi.model.response.ErrorRecordResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.EventRegistryResponseDTO;
import com.eric.governanceApi.governanceApi.repository.CommandRecordRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupMembershipRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.ErrorRecordRepository;
import com.eric.governanceApi.governanceApi.repository.EventRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final CommandRecordRepository commandRecordRepository;
    private final ErrorRecordRepository errorRecordRepository;
    private final EventRegistryRepository eventRegistryRepository;
    private final DeviceGroupMembershipRepository membershipRepository;

    public DeviceService(DeviceRepository deviceRepository,
                         CommandRecordRepository commandRecordRepository,
                         ErrorRecordRepository errorRecordRepository,
                         EventRegistryRepository eventRegistryRepository,
                         DeviceGroupMembershipRepository membershipRepository) {
        this.deviceRepository = deviceRepository;
        this.commandRecordRepository = commandRecordRepository;
        this.errorRecordRepository = errorRecordRepository;
        this.eventRegistryRepository = eventRegistryRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public List<DeviceSummaryDTO> listAll() {
        if (isAdmin()) {
            return deviceRepository.findAll().stream().map(DeviceSummaryDTO::from).toList();
        }
        String actorId = currentActorId();
        if (actorId == null) return List.of();
        return membershipRepository.findDevicesByKeycloakUserId(actorId).stream()
                .map(DeviceSummaryDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceDetailDTO getDevice(String deviceId) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));

        if (!isAdmin() && !canAccessDevice(device)) {
            // Return 404 to avoid leaking device existence to unauthorized users
            throw new ResourceNotFoundException("Device " + deviceId + " não encontrado.");
        }

        return DeviceDetailDTO.from(device);
    }

    @Transactional(readOnly = true)
    public Page<CommandRecordResponseDTO> getCommands(String deviceId, Pageable pageable) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        if (!isAdmin() && !canAccessDevice(device)) {
            throw new ResourceNotFoundException("Device " + deviceId + " não encontrado.");
        }
        return commandRecordRepository.findByTargetDevice_DeviceId(deviceId, pageable)
                .map(CommandRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<ErrorRecordResponseDTO> getErrors(String deviceId, Pageable pageable) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        if (!isAdmin() && !canAccessDevice(device)) {
            throw new ResourceNotFoundException("Device " + deviceId + " não encontrado.");
        }
        return errorRecordRepository.findByDevice_DeviceId(deviceId, pageable)
                .map(ErrorRecordResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public Page<EventRegistryResponseDTO> getEvents(String deviceId, Pageable pageable) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        if (!isAdmin() && !canAccessDevice(device)) {
            throw new ResourceNotFoundException("Device " + deviceId + " não encontrado.");
        }
        return eventRegistryRepository.findByDevice_DeviceId(deviceId, pageable)
                .map(EventRegistryResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public DeviceCertificateResponseDTO getCertificate(String deviceId) {
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));
        if (device.getCertificate() == null) {
            throw new ResourceNotFoundException("Device " + deviceId + " não possui certificado.");
        }
        return DeviceCertificateResponseDTO.from(device.getCertificate());
    }

    // todo (apenas adm pode ver todos os eventos de todos devices)
    @Transactional(readOnly = true)
    public Page<EventRegistryResponseDTO> listAllEvents(Pageable pageable) {
        return eventRegistryRepository.findAllByOrderByUploadedAtDesc(pageable)
                .map(EventRegistryResponseDTO::from);
    }

    // todo (apenas adm pode ver todos os eventos de todos devices)
    @Transactional(readOnly = true)
    public Page<CommandRecordResponseDTO> listAllCommands(Pageable pageable) {
        return commandRecordRepository.findAllByOrderBySentAtDesc(pageable)
                .map(CommandRecordResponseDTO::from);
    }

     // todo (apenas adm pode ver todos os eventos de todos devices)
    @Transactional(readOnly = true)
    public Page<ErrorRecordResponseDTO> listAllErrors(Pageable pageable) {
        return errorRecordRepository.findAllByOrderByReportedAtDesc(pageable)
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

    /** Returns true if the current user belongs to at least one group that contains the device. */
    private boolean canAccessDevice(Device device) {
        String actorId = currentActorId();
        if (actorId == null) return false;
        List<Device> accessible = membershipRepository.findDevicesByKeycloakUserId(actorId);
        return accessible.stream().anyMatch(d -> d.getId().equals(device.getId()));
    }
}
