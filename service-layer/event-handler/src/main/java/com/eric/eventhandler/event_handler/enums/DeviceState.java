package com.eric.eventhandler.event_handler.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum DeviceState {
    BOOT,
    NVS_INIT,
    WIFI_AP_MODE,
    WIFI_CONNECTING,
    TIME_SYNC,
    PROVISIONING,
    PROVISIONING_SUCCESS,
    MQTT_WAITING_CONNECT,    
    MQTT_INIT,
    OPERATIONAL,
    OTA_FOUND,
    OTA_DOWNLOADING,
    REBOOTING,
    ERROR,
    HTTP_INIT,
    HTTP_REQUEST,


    @JsonEnumDefaultValue
    UNKNOWN_STATE 
} 