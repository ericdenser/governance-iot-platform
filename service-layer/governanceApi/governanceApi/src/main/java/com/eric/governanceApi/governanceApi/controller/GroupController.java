package com.eric.governanceApi.governanceApi.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.model.request.AssignUserRequest;
import com.eric.governanceApi.governanceApi.model.request.CreateGroupRequest;
import com.eric.governanceApi.governanceApi.model.request.UpdateGroupRoleRequest;
import com.eric.governanceApi.governanceApi.model.response.DeviceGroupResponseDTO;
import com.eric.governanceApi.governanceApi.model.response.GroupDeviceMemberDTO;
import com.eric.governanceApi.governanceApi.model.response.GroupMemberResponseDTO;
import com.eric.governanceApi.governanceApi.service.GroupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    // ── Groups ────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<DeviceGroupResponseDTO> create(@Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.createGroup(request));
    }

    @GetMapping
    public ResponseEntity<List<DeviceGroupResponseDTO>> list() {
        return ResponseEntity.ok(groupService.listGroups());
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<DeviceGroupResponseDTO> get(@PathVariable String groupId) {
        return ResponseEntity.ok(groupService.getGroup(groupId));
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> delete(@PathVariable String groupId) {
        groupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    // ── Device membership ─────────────────────────────────────────────────────

    @GetMapping("/{groupId}/devices")
    public ResponseEntity<List<GroupDeviceMemberDTO>> listDevices(@PathVariable String groupId) {
        return ResponseEntity.ok(groupService.listDevicesInGroup(groupId));
    }

    @PutMapping("/{groupId}/devices/{deviceId}")
    public ResponseEntity<Void> addDevice(@PathVariable String groupId, @PathVariable String deviceId) {
        groupService.addDevice(groupId, deviceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/devices/{deviceId}")
    public ResponseEntity<Void> removeDevice(@PathVariable String groupId, @PathVariable String deviceId) {
        groupService.removeDevice(groupId, deviceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/devices/{deviceId}/membership")
    public ResponseEntity<Void> checkMembership(@PathVariable String groupId, @PathVariable String deviceId) {
        return groupService.checkDeviceMembership(groupId, deviceId)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    // ── User assignment ───────────────────────────────────────────────────────

    @GetMapping("/{groupId}/users")
    public ResponseEntity<List<GroupMemberResponseDTO>> listUsers(@PathVariable String groupId) {
        return ResponseEntity.ok(groupService.listUsers(groupId));
    }

    @PutMapping("/{groupId}/users")
    public ResponseEntity<GroupMemberResponseDTO> assignUser(
            @PathVariable String groupId,
            @Valid @RequestBody AssignUserRequest request) {
        return ResponseEntity.ok(groupService.assignUser(groupId, request));
    }

    @DeleteMapping("/{groupId}/users/{keycloakUserId}")
    public ResponseEntity<Void> removeUser(@PathVariable String groupId, @PathVariable String keycloakUserId) {
        groupService.removeUser(groupId, keycloakUserId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{groupId}/users/{keycloakUserId}")
    public ResponseEntity<GroupMemberResponseDTO> updateUserRole(
            @PathVariable String groupId,
            @PathVariable String keycloakUserId,
            @Valid @RequestBody UpdateGroupRoleRequest request) {
        return ResponseEntity.ok(groupService.updateUserRole(groupId, keycloakUserId, request));
    }
}
