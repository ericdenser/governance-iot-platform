package com.eric.governanceApi.governanceApi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DeviceErrorRequest {
    
    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("fw_version")
    private Float firmwareVersion;
    
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