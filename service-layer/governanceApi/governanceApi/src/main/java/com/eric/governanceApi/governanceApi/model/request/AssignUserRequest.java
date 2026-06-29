package com.eric.governanceApi.governanceApi.model.request;

import com.eric.governanceApi.governanceApi.enums.GroupRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssignUserRequest(
        @NotBlank String keycloakUserId,
        @NotNull GroupRole role
) {}
