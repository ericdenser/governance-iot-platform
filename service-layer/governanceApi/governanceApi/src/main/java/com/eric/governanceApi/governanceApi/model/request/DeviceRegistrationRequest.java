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
    private String publicKey;

    @NotBlank
    @Pattern(regexp = ValidationPatterns.UUID_REGEX, message = "provisioningToken " + ValidationPatterns.UUID_MESSAGE)
    private String provisioningToken;
}
