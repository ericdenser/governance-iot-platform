package com.eric.governanceApi.governanceApi.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.ErrorCode;
import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroup;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroupMembership;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;
import com.eric.governanceApi.governanceApi.model.entity.ProvisioningToken;
import com.eric.governanceApi.governanceApi.model.request.DeviceRegistrationRequest;
import com.eric.governanceApi.governanceApi.model.request.RegisterDeviceRequest;
import com.eric.governanceApi.governanceApi.model.response.CreatedKeycloakClient;
import com.eric.governanceApi.governanceApi.model.response.DeviceCredentialsDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupMembershipRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareRepository;
import com.eric.governanceApi.governanceApi.repository.FirmwareVersionRepository;
import com.eric.governanceApi.governanceApi.repository.ProvisioningTokenRepository;
import com.eric.governanceApi.governanceApi.repository.UserGroupAssignmentRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DeviceProvisioningService {

    private final DeviceRepository deviceRepository;
    private final ProvisioningTokenRepository tokenRepository;
    private final FirmwareVersionRepository firmwareVersionRepository;
    private final DeviceGroupRepository deviceGroupRepository;
    private final DeviceGroupMembershipRepository deviceGroupMembershipRepository;
    private final UserGroupAssignmentRepository assignmentRepository;
    private final KeycloakDeviceClientService keycloakDeviceClientService;

    @Value("${provisioning.token-ttl-seconds}")
    private int tokenttl;

    public DeviceProvisioningService(DeviceRepository deviceRepository,
                                     ProvisioningTokenRepository tokenRepository,
                                     FirmwareRepository firmwareRepository,
                                     DeviceGroupRepository deviceGroupRepository,
                                     DeviceGroupMembershipRepository deviceGroupMembershipRepository,
                                     UserGroupAssignmentRepository assignmentRepository, 
                                     FirmwareVersionRepository firmwareVersionRepository, 
                                     KeycloakDeviceClientService keycloakDeviceClientService) {
        this.deviceRepository = deviceRepository;
        this.tokenRepository = tokenRepository;
        this.firmwareVersionRepository = firmwareVersionRepository;
        this.deviceGroupRepository = deviceGroupRepository;
        this.deviceGroupMembershipRepository = deviceGroupMembershipRepository;
        this.assignmentRepository = assignmentRepository;
        this.keycloakDeviceClientService = keycloakDeviceClientService;
    }

    @Transactional
    public ProvisioningToken registerDevice(RegisterDeviceRequest request) {
        return registerDevice(request, tokenttl);
    }

    @Transactional
    public ProvisioningToken registerDevice(RegisterDeviceRequest request, int ttlSeconds) {
        String deviceName = request.deviceName();

        if (deviceName == null || deviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("The field 'deviceName' is missing.");
        }

        if (!isAdmin()) {
            if (request.groupId() == null || request.groupId().isBlank()) {
                throw new SecurityException("Usuários não-admin devem especificar um grupo ao provisionar.");
            }
            String actorId = currentActor()[0];
            boolean allowed = assignmentRepository
                    .findByIdKeycloakUserIdAndGroupGroupId(actorId, request.groupId())
                    .map(a -> a.getRole() == GroupRole.MEMBER || a.getRole() == GroupRole.OWNER)
                    .orElse(false);
            if (!allowed) {
                throw new SecurityException("Sem permissão de MEMBER/OWNER no grupo " + request.groupId() + ".");
            }
        }

        Device newDevice = new Device();
        newDevice.setName(deviceName);
        newDevice.setStatus(DeviceStatus.PENDING);

        deviceRepository.save(newDevice);

        if (request.groupId() != null && !request.groupId().isBlank()) {
            DeviceGroup group = deviceGroupRepository.findByGroupId(request.groupId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.GROUP_NOT_FOUND,
                    "Grupo " + request.groupId() + " não encontrado."));

            String[] actor = currentActor();
            deviceGroupMembershipRepository.save(new DeviceGroupMembership(newDevice, group, actor[0], actor[1]));
            log.info("Device {} adicionado ao grupo {}", newDevice.getDeviceId(), request.groupId());
        } else {
            log.info("Nenhum groupId informado ao device {}.", newDevice.getDeviceId());
        }

        ProvisioningToken provisioningToken = new ProvisioningToken(newDevice, ttlSeconds);
        tokenRepository.save(provisioningToken);
        return provisioningToken;
    }

    // Compensação do generatePackage: se a geração do pacote falhar depois do
    // registro, remove device+token na hora em vez de esperar o token expirar (24h).
    @Transactional
    public void discardPendingDevice(String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            if (device.getStatus() != DeviceStatus.PENDING) return;
            deviceGroupMembershipRepository.deleteByDeviceId(device.getId());
            deviceRepository.delete(device);
            log.info("Device PENDING {} descartado após falha na geração do flash package.", deviceId);
        });
    }

    @Transactional
    public DeviceCredentialsDTO processDeviceRegistration(DeviceRegistrationRequest request) {
        log.info("Procurando token: {}", request.getProvisioningToken());
        // Se o token não foi emitido
        ProvisioningToken token = tokenRepository.findByToken(request.getProvisioningToken())
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PROVISIONING_TOKEN_INVALID,
                "Token de provisionamento inválido."));
        
        token.validate(); 

        // Se o status não é Pendente
        Device device = token.getDevice();
        if (device.getStatus() != DeviceStatus.PENDING) {
            log.info("Device={} not in pending status", device.getDeviceId());
            throw new SecurityException("Device not in PENDING status.");
        }

        // Valida que o device_id do request é o do device dono do token
        if (!device.getDeviceId().equals(request.getDeviceId())) {
            throw new SecurityException("Device ID does not match the provisioning token");
        }

        // Queima o token
        token.setUsed(true);

        FirmwareVersion provisioningFirmware = firmwareVersionRepository.findFirstByFirmware_ProvisioningFirmwareTrueOrderByUploadedAtDesc()
                                                                        .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.FIRMWARE_NOT_FOUND, 
                                                                            "No provisioning firmware registered"));

        CreatedKeycloakClient created = keycloakDeviceClientService.createClient(device.getDeviceId());
        device.setDeviceId(request.getDeviceId());
        device.setMacAddress(request.getMacAddress());
        device.setStatus(DeviceStatus.PROVISIONING);            
        device.setLastSeen(Instant.now());
        device.setFirmwareVersion(provisioningFirmware);

        // Propaga o autor do token para o device: quem gerou o zip que originou esse provisioning
        device.setIssuedByActorId(token.getCreatedByActorId());
        device.setIssuedByUsername(token.getCreatedByUsername());  

        // Retorna o PEM do certificado para o ESP32 guardar na memória (NVS)
        return new DeviceCredentialsDTO(created.clientId(), created.clientSecret());
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private String[] currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return new String[]{ jwt.getSubject(), jwt.getClaimAsString("preferred_username") };
        }
        return new String[]{ null, null };
    }
}