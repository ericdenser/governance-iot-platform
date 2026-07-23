package com.eric.governanceApi.governanceApi.service;

import org.springframework.stereotype.Service;
import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;

import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DeviceRevokeService {
    private final DeviceRepository deviceRepository;
    private final KeycloakDeviceClientService keycloakDeviceClientService;

    public DeviceRevokeService(DeviceRepository deviceRepository, KeycloakDeviceClientService keycloakDeviceClientService) {
        this.deviceRepository = deviceRepository;
        this.keycloakDeviceClientService = keycloakDeviceClientService;
    }



    @Auditable(action = AuditAction.DEVICE_REVOKED, targetType = "DEVICE", targetIdArg = 0)
    @Transactional
    public String revokeDevice(String deviceId) throws Exception {

        log.info("Iniciando processo de revogação para o dispositivo ID: {}", deviceId);
        Device device = deviceRepository.findByDeviceId(deviceId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.DEVICE_NOT_FOUND,
                "Device " + deviceId + " não encontrado."));

        
        if (device.getStatus() == DeviceStatus.REVOKED) {
            log.warn("Device {} already revoked.", deviceId);
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                "Device " + deviceId + " já revogado.");
        }

        keycloakDeviceClientService.deleteClient(device.getKeycloakInternalId());
        device.setStatus(DeviceStatus.REVOKED);
        log.info("Device {} revoked (Keycloak client {} deleted)", deviceId, device.getKeycloakInternalId());

        return "Device " + deviceId + " revoked successfully.";
    }
}
