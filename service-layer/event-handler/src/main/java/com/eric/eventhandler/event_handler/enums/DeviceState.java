package com.eric.eventhandler.event_handler.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum DeviceState {
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
    OTA_SUCCESSFUL,
    BOOT_AUDIT,
    FIRMWARE_ROLLBACK,
    REBOOTING,
    ERROR,
    HTTP_INIT,
    HTTP_REQUEST,
    WAITING_RESPONSE,
    COMMAND_COMPLETE,

    @JsonEnumDefaultValue
    UNKNOWN_STATE;

    @JsonCreator
    public static DeviceState fromValue(String value) {
        if (value == null) return UNKNOWN_STATE;
        try {
            int ordinal = Integer.parseInt(value);
            DeviceState[] values = values();
            if (ordinal >= 0 && ordinal < values.length) return values[ordinal];
            return UNKNOWN_STATE;
        } catch (NumberFormatException e) {
            try {
                return DeviceState.valueOf(value);
            } catch (IllegalArgumentException ex) {
                return UNKNOWN_STATE;
            }
        }
    }
}