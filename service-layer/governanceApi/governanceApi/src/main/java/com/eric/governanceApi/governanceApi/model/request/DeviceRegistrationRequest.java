package com.eric.governanceApi.governanceApi.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceRegistrationRequest {

    @NotBlank
    @Pattern(regexp = ValidationPatterns.UUID_REGEX, message = "deviceId " + ValidationPatterns.UUID_MESSAGE)
    private String deviceId;

    @NotBlank @Size(max = 32)
    private String macAddress;

    @NotBlank
    @Pattern(regexp = ValidationPatterns.PROVISIONING_TOKEN_REGEX,
             message = "provisioningToken " + ValidationPatterns.PROVISIONING_TOKEN_MESSAGE)
    private String provisioningToken;
}
