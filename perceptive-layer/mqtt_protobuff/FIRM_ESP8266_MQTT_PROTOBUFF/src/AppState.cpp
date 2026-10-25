#include "AppState.h"
#include <Arduino.h>

DeviceState AppState::_current  = DeviceState::NVS_INIT;
DeviceState AppState::_previous = DeviceState::NVS_INIT;

void AppState::transition(DeviceState next, const char* source) {
    if (next == _current) return;
    Serial.printf("[AppState] %s -> %s (%s)\n", toString(_current), toString(next), source);
    _previous = _current;
    _current  = next;
}

DeviceState AppState::get()      { return _current; }
DeviceState AppState::previous() { return _previous; }
bool AppState::is(DeviceState s) { return _current == s; }

const char* AppState::toString(DeviceState state) {
    switch (state) {
        case DeviceState::NVS_INIT:             return "NVS_INIT";
        case DeviceState::WIFI_AP_MODE:         return "WIFI_AP_MODE";
        case DeviceState::WIFI_CONNECTING:      return "WIFI_CONNECTING";
        case DeviceState::TIME_SYNC:            return "TIME_SYNC";
        case DeviceState::PROVISIONING:         return "PROVISIONING";
        case DeviceState::PROVISIONING_SUCCESS: return "PROVISIONING_SUCCESS";
        case DeviceState::MQTT_WAITING_CONNECT: return "MQTT_WAITING_CONNECT";
        case DeviceState::MQTT_INIT:            return "MQTT_INIT";
        case DeviceState::OPERATIONAL:          return "OPERATIONAL";
        case DeviceState::OTA_FOUND:            return "OTA_FOUND";
        case DeviceState::OTA_SUCCESSFUL:       return "OTA_SUCCESSFUL";
        case DeviceState::BOOT_AUDIT:           return "BOOT_AUDIT";
        case DeviceState::FIRMWARE_ROLLBACK:    return "FIRMWARE_ROLLBACK";
        case DeviceState::OTA_DOWNLOADING:      return "OTA_DOWNLOADING";
        case DeviceState::REBOOTING:            return "REBOOTING";
        case DeviceState::ERROR:                return "ERROR";
        case DeviceState::HTTP_INIT:            return "HTTP_INIT";
        case DeviceState::HTTP_REQUEST:         return "HTTP_REQUEST";
        case DeviceState::WAITING_RESPONSE:     return "WAITING_RESPONSE";
        case DeviceState::COMMAND_COMPLETE:     return "COMMAND_COMPLETE";
        case DeviceState::SENSORS_INIT:         return "SENSORS_INIT";
        case DeviceState::CRITICAL_BATTERY:     return "CRITICAL_BATTERY";
    }
    return "UNKNOWN";
}
