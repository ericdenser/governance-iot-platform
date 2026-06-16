package com.eric.governanceApi.governanceApi.enums;

public enum DeviceError {
    NONE,
    NVS_INIT_FAIL,
    NVS_WRITE_FAIL,
    NVS_COMMIT_FAIL,
    NVS_LOAD_FAIL,
    WIFI_TIMEOUT,
    TIME_SYNC_FAIL,
    PROVISIONING_REQUEST_FAIL,
    PROVISIONING_RESPONSE_INVALID,
    MQTT_INIT_FAIL,
    MQTT_DISCONNECTED,
    CRASH_ROLLBACK,
    OTA_FAIL,
    HTTP_INIT_FAIL,
    HTTP_REQUEST_FAIL,
    MEMORY_ALOCATION_FAIL,
    KEY_GENERATION_FAIL,
    CSR_SUBJECT_NAME_FAIL,
    CSR_TO_PEM_FAIL,
    KEY_MISSING,
    CERT_MISSING,
    TOPIC_SUBSCRIBE_FAIL,
    WATCHDOG_INIT_FAIL,
    WATCHDOG_ADD_FAIL,
    WATCHDOG_REMOVE_FAIL,
    TOPIC_FORMAT_INVALID,
    COMMAND_RESPONSE_INVALID,
    DEVICE_ID_MISSING,
    WIFI_CREDENTIALS_MISSING,
    FIRMWARE_VERSION_MISSING,
    FIRMWARE_ROLLBACK_FAILED,
    UNKNOWN;

    public static DeviceError fromCode(String code) {
        try {
            int ordinal = Integer.parseInt(code);
            DeviceError[] values = values();
            if (ordinal >= 0 && ordinal < values.length) return values[ordinal];
        } catch (NumberFormatException ignored) {}
        return UNKNOWN;
    }
};
