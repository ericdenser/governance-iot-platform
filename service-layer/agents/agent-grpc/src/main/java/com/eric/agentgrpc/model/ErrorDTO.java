package com.eric.agentgrpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDTO(

    String device_id,
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

){}
