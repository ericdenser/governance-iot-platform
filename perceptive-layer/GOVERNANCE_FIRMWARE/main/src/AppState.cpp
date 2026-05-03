#include "AppState.h"
#include "esp_log.h"

static const char* TAG = "AppState";

//  Variáveis estáticas 
DeviceState      AppState::_current  = DeviceState::BOOT;
DeviceState      AppState::_previous = DeviceState::BOOT;
AppError         AppState::_lastError;
SemaphoreHandle_t AppState::_mutex   = NULL;


//  init() — cria o mutex; chamar uma única vez 
void AppState::init() {
    _mutex = xSemaphoreCreateMutex();
    ESP_LOGI(TAG, "AppState inicializado. Estado inicial: %s", toString(_current));
}


//  transition() — única forma de mudar estado; thread-safe + log automático
void AppState::transition(DeviceState next, const Source& source) {
    if (_mutex == NULL) return;

    xSemaphoreTake(_mutex, portMAX_DELAY);
    _previous = _current;
    _current  = next;
    xSemaphoreGive(_mutex);

    ESP_LOGI(TAG, "[%s] on %s | %s --> %s",
             source.className.c_str(),
             source.method.c_str(),
             toString(_previous),
             toString(_current));
}


//  setError() — registra contexto + transiciona para ERROR automaticamente
void AppState::setError(ErrorCode code, const std::string& msg, const Source& source) {
    if (_mutex == NULL) return;

    xSemaphoreTake(_mutex, portMAX_DELAY);
    _lastError.code   = code;
    _lastError.msg    = msg;
    _lastError.source = source;
    _previous = _current;
    _current  = DeviceState::ERROR;
    xSemaphoreGive(_mutex);

    ESP_LOGE(TAG, "[%s::%s] ERRO (%s): %s",
             source.className.c_str(),
             source.method.c_str(),
             toString(code),
             msg.c_str());
}


//  Leitores
DeviceState AppState::get() {
    if (_mutex == NULL) return _current;
    xSemaphoreTake(_mutex, portMAX_DELAY);
    DeviceState s = _current;
    xSemaphoreGive(_mutex);
    return s;
}

DeviceState AppState::previous() {
    if (_mutex == NULL) return _previous;
    xSemaphoreTake(_mutex, portMAX_DELAY);
    DeviceState s = _previous;
    xSemaphoreGive(_mutex);
    return s;
}

bool AppState::is(DeviceState state) {
    return get() == state;
}

AppError AppState::getError() {
    if (_mutex == NULL) return _lastError;
    xSemaphoreTake(_mutex, portMAX_DELAY);
    AppError e = _lastError;
    xSemaphoreGive(_mutex);
    return e;
}

void AppState::clearError() {
    if (_mutex == NULL) return;
    xSemaphoreTake(_mutex, portMAX_DELAY);
    _lastError = AppError{};
    xSemaphoreGive(_mutex);
}

//  toString() — para logs legíveis ===================================
const char* AppState::toString(DeviceState state) {
    switch (state) {
        case DeviceState::BOOT:                 return "BOOT";
        case DeviceState::NVS_INIT:             return "NVS_INIT";
        case DeviceState::WIFI_AP_MODE:         return "WIFI_AP_MODE";
        case DeviceState::WIFI_CONNECTING:      return "WIFI_CONNECTING";
        case DeviceState::TIME_SYNC:            return "TIME_SYNC";
        case DeviceState::PROVISIONING:         return "PROVISIONING";
        case DeviceState::MQTT_WAITING_CONNECT: return "MQTT_WAITING_CONNECT";
        case DeviceState::MQTT_INIT:            return "MQTT_INIT";
        case DeviceState::OPERATIONAL:          return "OPERATIONAL";
        case DeviceState::OTA_FOUND:            return "OTA_FOUND";
        case DeviceState::OTA_DOWNLOADING:      return "OTA_DOWNLOADING";
        case DeviceState::REBOOTING:            return "REBOOTING";
        case DeviceState::ERROR:                return "ERROR";
        case DeviceState::HTTP_INIT:            return "HTTP_INIT";
        case DeviceState::HTTP_REQUEST:         return "HTTP_REQUEST";
        default:                                return "UNKNOWN";
    }
}

const char* AppState::toString(ErrorCode code) {
    switch (code) {
        case ErrorCode::NONE:                          return "NONE";
        case ErrorCode::NVS_INIT_FAIL:                 return "NVS_INIT_FAIL";
        case ErrorCode::NVS_WRITE_FAIL:                return "NVS_WRITE_FAIL";
        case ErrorCode::NVS_COMMIT_FAIL:               return "NVS_COMMIT_FAIL";
        case ErrorCode::WIFI_TIMEOUT:                  return "WIFI_TIMEOUT";
        case ErrorCode::TIME_SYNC_FAIL:                return "TIME_SYNC_FAIL";
        case ErrorCode::PROVISIONING_REQUEST_FAIL:     return "PROVISIONING_REQUEST_FAIL";
        case ErrorCode::PROVISIONING_RESPONSE_INVALID: return "PROVISIONING_RESPONSE_INVALID";
        case ErrorCode::MQTT_INIT_FAIL:                return "MQTT_INIT_FAIL";
        case ErrorCode::OTA_FAIL:                      return "OTA_FAIL";
        case ErrorCode::HTTP_INIT_FAIL:                return "HTTP_INIT_FAIL";
        case ErrorCode::HTTP_REQUEST_FAIL:             return "HTTP_REQUEST_FAIL";
        case ErrorCode::MEMORY_ALOCATION_FAIL:         return "MEMORY_ALOCATION_FAIL";
        case ErrorCode::KEY_GENERATION_FAIL:           return "KEY_GENERATION_FAIL";
        case ErrorCode::CSR_SUBJECT_NAME_FAIL:         return "CSR_SUBJECT_NAME_FAIL";
        case ErrorCode::CSR_TO_PEM_FAIL:               return "CSR_TO_PEM_FAIL";
        case ErrorCode::WATCHDOG_INIT_FAIL:            return "WATCHDG_INIT_FAIL";
        case ErrorCode::WATCHDOG_ADD_FAIL:             return "WATCHDG_ADD_FAIL";
        case ErrorCode::WATCHDOG_REMOVE_FAIL:          return "WATCHDG_REMOVE_FAIL";
        case ErrorCode::COMMAND_RESPONSE_INVALID:      return "COMMAND_RESPONSE_INVALID";
        case ErrorCode::TOPIC_FORMAT_INVALID:          return "TOPIC_FORMAT_INVALID";
        case ErrorCode::UNKNOWN:                       return "UNKNOWN";
        default:                                       return "UNKNOWN";
    }
}