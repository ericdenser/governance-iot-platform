package com.eric.governanceApi.governanceApi.model.request;

import lombok.Data;

@Data
public class DeviceRegistrationRequest {
    
    private String deviceId;
    private String macAddress;
    private String publicKey;
    private String provisioningToken;

}
