package com.eric.governanceApi.governanceApi.model.request;

import com.eric.governanceApi.governanceApi.enums.GroupRole;

import jakarta.validation.constraints.NotNull;

public record UpdateGroupRoleRequest(
        @NotNull GroupRole role
) {}
