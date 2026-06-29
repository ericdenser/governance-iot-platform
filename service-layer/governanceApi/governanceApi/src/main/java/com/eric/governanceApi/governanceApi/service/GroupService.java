package com.eric.governanceApi.governanceApi.service;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.audit.Auditable;
import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.enums.GroupRole;
import com.eric.governanceApi.governanceApi.exceptions.ConflictException;
import com.eric.governanceApi.governanceApi.exceptions.ResourceNotFoundException;
import com.eric.governanceApi.governanceApi.model.entity.Device;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroup;
import com.eric.governanceApi.governanceApi.model.entity.DeviceGroupMembership;
import com.eric.governanceApi.governanceApi.model.entity.UserGroupAssignment;
import com.eric.governanceApi.governanceApi.model.request.AssignUserRequest;
import com.eric.governanceApi.governanceApi.model.request.CreateGroupRequest;
import com.eric.governanceApi.governanceApi.model.response.DeviceGroupResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.DeviceSummaryDTO;
import com.eric.governanceApi.governanceApi.model.response.GroupMemberResponseDTO;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupMembershipRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceGroupRepository;
import com.eric.governanceApi.governanceApi.repository.DeviceRepository;
import com.eric.governanceApi.governanceApi.repository.UserGroupAssignmentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final DeviceGroupRepository groupRepository;
    private final DeviceGroupMembershipRepository membershipRepository;
    private final UserGroupAssignmentRepository assignmentRepository;
    private final DeviceRepository deviceRepository;

    // ── Groups ────────────────────────────────────────────────────────────────

    @Auditable(action = AuditAction.GROUP_CREATED, targetType = "GROUP")
    @Transactional
    public DeviceGroupResponseDTO createGroup(CreateGroupRequest request) {
        if (groupRepository.existsByName(request.name())) {
            throw new ConflictException("Grupo '" + request.name() + "' já existe.");
        }
        DeviceGroup group = new DeviceGroup();
        group.setName(request.name());
        group.setDescription(request.description());
        groupRepository.save(group);
        log.info("Grupo '{}' criado (groupId={})", group.getName(), group.getGroupId());
        return DeviceGroupResponseDTO.from(group);
    }

    @Transactional(readOnly = true)
    public List<DeviceGroupResponseDTO> listGroups() {
        String actorId = currentActorId();
        if (actorId == null || isAdmin()) {
            return groupRepository.findAll().stream().map(DeviceGroupResponseDTO::from).toList();
        }
        return assignmentRepository.findByIdKeycloakUserId(actorId).stream()
                .map(a -> DeviceGroupResponseDTO.from(a.getGroup()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceGroupResponseDTO getGroup(String groupId) {
        return DeviceGroupResponseDTO.from(findGroupOrThrow(groupId));
    }

    @Auditable(action = AuditAction.GROUP_DELETED, targetType = "GROUP", targetIdArg = 0)
    @Transactional
    public void deleteGroup(String groupId) {
        DeviceGroup group = findGroupOrThrow(groupId);
        groupRepository.delete(group);
        log.info("Grupo '{}' removido.", groupId);
    }

    // ── Device membership ─────────────────────────────────────────────────────

    @Auditable(action = AuditAction.DEVICE_ADDED_TO_GROUP, targetType = "DEVICE", targetIdArg = 1)
    @Transactional
    public void addDevice(String groupId, String deviceId) {
        DeviceGroup group = findGroupOrThrow(groupId);
        Device device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device " + deviceId + " não encontrado."));

        if (membershipRepository.existsByDeviceDeviceIdAndGroupGroupId(deviceId, groupId)) {
            throw new ConflictException("Device " + deviceId + " já pertence ao grupo " + groupId + ".");
        }

        String[] actor = currentActor();
        membershipRepository.save(new DeviceGroupMembership(device, group, actor[0], actor[1]));
        log.info("Device {} adicionado ao grupo {}", deviceId, groupId);
    }

    @Auditable(action = AuditAction.DEVICE_REMOVED_FROM_GROUP, targetType = "DEVICE", targetIdArg = 1)
    @Transactional
    public void removeDevice(String groupId, String deviceId) {
        DeviceGroupMembership membership = membershipRepository
                .findByDeviceDeviceIdAndGroupGroupId(deviceId, groupId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device " + deviceId + " não pertence ao grupo " + groupId + "."));
        membershipRepository.delete(membership);
        log.info("Device {} removido do grupo {}", deviceId, groupId);
    }

    @Transactional(readOnly = true)
    public List<DeviceSummaryDTO> listDevicesInGroup(String groupId) {
        DeviceGroup group = findGroupOrThrow(groupId);

        if (!isAdmin()) {
            String actorId = currentActorId();
            if (actorId == null || !assignmentRepository.existsByIdKeycloakUserIdAndGroupGroupId(actorId, groupId)) {
                throw new ResourceNotFoundException("Grupo " + groupId + " não encontrado.");
            }
        }

        return membershipRepository.findByGroupId(group.getId()).stream()
                .map(m -> DeviceSummaryDTO.from(m.getDevice()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean checkDeviceMembership(String groupId, String deviceId) {
        findGroupOrThrow(groupId);
        return membershipRepository.existsByDeviceDeviceIdAndGroupGroupId(deviceId, groupId);
    }

    // ── User assignment ───────────────────────────────────────────────────────

    @Auditable(action = AuditAction.USER_ASSIGNED_TO_GROUP, targetType = "GROUP", targetIdArg = 0)
    @Transactional
    public GroupMemberResponseDTO assignUser(String groupId, AssignUserRequest request) {
        DeviceGroup group = findGroupOrThrow(groupId);

        String[] actor = currentActor();

        // Upsert: se já existe, atualiza o papel
        UserGroupAssignment assignment = assignmentRepository
                .findByIdKeycloakUserIdAndGroupGroupId(request.keycloakUserId(), groupId)
                .orElseGet(() -> new UserGroupAssignment(
                        request.keycloakUserId(), group, request.role(), actor[0], actor[1]));

        assignment.setRole(request.role());
        assignmentRepository.save(assignment);
        log.info("Usuário {} atribuído ao grupo {} com papel {}", request.keycloakUserId(), groupId, request.role());
        return GroupMemberResponseDTO.from(assignment);
    }

    @Auditable(action = AuditAction.USER_REMOVED_FROM_GROUP, targetType = "GROUP", targetIdArg = 0)
    @Transactional
    public void removeUser(String groupId, String keycloakUserId) {
        UserGroupAssignment assignment = assignmentRepository
                .findByIdKeycloakUserIdAndGroupGroupId(keycloakUserId, groupId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Usuário " + keycloakUserId + " não pertence ao grupo " + groupId + "."));
        assignmentRepository.delete(assignment);
        log.info("Usuário {} removido do grupo {}", keycloakUserId, groupId);
    }

    @Transactional(readOnly = true)
    public List<GroupMemberResponseDTO> listUsers(String groupId) {
        DeviceGroup group = findGroupOrThrow(groupId);
        return assignmentRepository.findByGroupId(group.getId()).stream()
                .map(GroupMemberResponseDTO::from)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DeviceGroup findGroupOrThrow(String groupId) {
        return groupRepository.findByGroupId(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Grupo " + groupId + " não encontrado."));
    }

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

    /** Returns [actorId, username]. */
    private String[] currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return new String[]{ jwt.getSubject(), jwt.getClaimAsString("preferred_username") };
        }
        return new String[]{ null, null };
    }
}
