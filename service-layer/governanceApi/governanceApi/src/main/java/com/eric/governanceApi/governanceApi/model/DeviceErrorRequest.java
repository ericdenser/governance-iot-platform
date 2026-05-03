package com.eric.governanceApi.governanceApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DeviceErrorRequest {
    
    private String mac;
    
    @JsonProperty("firmware_version")
    private Integer firmwareVersion;
    
    private String ssid;
    
    private String ip;
    
    @JsonProperty("current_process")
    private String currentProcess;
    
    @JsonProperty("error_code")
    private String errorCode;
    
    @JsonProperty("error_msg")
    private String errorMsg;
    
    @JsonProperty("error_source")
    private String errorSource;
}