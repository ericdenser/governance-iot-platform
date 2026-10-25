#ifndef APPSTATE_H
#define APPSTATE_H

// Mesma ordem do governance_core: o indice vai no campo `state` do DeviceStatus
// e o event-handler converte pelo ordinal. NAO reordenar.
enum class DeviceState {
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
    SENSORS_INIT,
    CRITICAL_BATTERY
};

// Mesma ordem do governance_core: vai como ordinal no error_code do DeviceError. NAO reordenar.
enum class ErrorCode {
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
    UNKNOWN
};

// Single-thread: sem mutex e sem fila de erros
class AppState {
public:
    static void transition(DeviceState next, const char* source);
    static DeviceState get();
    static DeviceState previous();
    static bool is(DeviceState state);
    static const char* toString(DeviceState state);

private:
    static DeviceState _current;
    static DeviceState _previous;
};

#endif
