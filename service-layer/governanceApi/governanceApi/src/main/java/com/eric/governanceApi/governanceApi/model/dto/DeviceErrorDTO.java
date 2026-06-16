package com.eric.governanceApi.governanceApi.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeviceErrorDTO (

    @JsonProperty("device_id")
    String deviceId,

    String mac,

    String firmware_version,
    
    String ssid,
    
    @JsonProperty("error_code")
    String errorCode,
    
    @JsonProperty("error_msg")
    String errorMsg,
    
    @JsonProperty("error_source")
    String errorSource,

    String extra,

    boolean resolved

){
    
}