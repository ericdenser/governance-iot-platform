package com.eric.governanceApi.governanceApi.model.request;

import com.eric.governanceApi.governanceApi.enums.GroupRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AssignUserRequest(

        @NotBlank
        @Pattern(regexp = ValidationPatterns.UUID_REGEX, message = "keycloakUserId " + ValidationPatterns.UUID_MESSAGE)
        String keycloakUserId,

        @NotNull
        GroupRole role
) {}
