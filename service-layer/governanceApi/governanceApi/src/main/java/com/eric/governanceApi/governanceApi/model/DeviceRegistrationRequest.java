package com.eric.governanceApi.governanceApi.model;

import lombok.Data;

@Data
public class DeviceRegistrationRequest {
    
    private String macAddress;
    private String publicKey;
    private String provisioningToken;

}
