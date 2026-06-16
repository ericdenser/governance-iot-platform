#include "AppState.h"
#include "esp_log.h"
#include "nvs.h"

static const char* TAG            = "AppState";
static const char* NVS_ERR_NS     = "error_store";
static const char* NVS_MASK_KEY   = "active_mask";

// ---- Variáveis estáticas ----

DeviceState           AppState::_current          = DeviceState::NVS_INIT;
DeviceState           AppState::_previous         = DeviceState::NVS_INIT;
SemaphoreHandle_t     AppState::_mutex            = NULL;
std::queue<AppError>  AppState::_errorQueue;
uint32_t              AppState::_activeErrorMask  = 0;

// ---- Helpers de arquivo ----

static uint32_t error_bit(ErrorCode code) {
    uint32_t idx = static_cast<uint32_t>(code);
    if (idx == 0 || idx >= 32) return 0; // NONE ou fora do range
    return (1u << idx);
}

static void nvs_save_mask(uint32_t mask) {
    nvs_handle_t h;
    if (nvs_open(NVS_ERR_NS, NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_u32(h, NVS_MASK_KEY, mask);
    nvs_commit(h);
    nvs_close(h);
}

// ---- Inicialização ----

void AppState::init() {
    _mutex = xSemaphoreCreateMutex();
    ESP_LOGI(TAG, "AppState inicializado. Estado inicial: %s", toString(_current));
}

void AppState::loadPersistedErrors() {
    nvs_handle_t h;
    if (nvs_open(NVS_ERR_NS, NVS_READONLY, &h) == ESP_OK) {
        uint32_t mask = 0;
        if (nvs_get_u32(h, NVS_MASK_KEY, &mask) == ESP_OK) {
            xSemaphoreTake(_mutex, portMAX_DELAY);
            _activeErrorMask = mask;
            xSemaphoreGive(_mutex);
            ESP_LOGI(TAG, "activeErrorMask restaurado do NVS: 0x%08lX", (unsigned long)mask);
        }
        nvs_close(h);
    }
}

// ---- Transição de estado ----

void AppState::transition(DeviceState next, const Source& source) {
    if (_mutex == NULL) return;

    xSemaphoreTake(_mutex, portMAX_DELAY);
    _previous = _current;
    _current  = next;
    xSemaphoreGive(_mutex);

    ESP_LOGI(TAG, "[%s::%s] %s --> %s",
             source.className.c_str(),
             source.method.c_str(),
             toString(_previous),
             toString(_current));
}

// ---- Registro de erro ----

void AppState::setError(ErrorCode code, const std::string& msg, const Source& source,
                        const std::map<std::string, std::string>& details) {
    if (_mutex == NULL) return;

    AppError evt;
    evt.code     = code;
    evt.msg      = msg;
    evt.source   = source;
    evt.details  = details;
    evt.resolved = false;

    uint32_t maskToSave;
    xSemaphoreTake(_mutex, portMAX_DELAY);
    _errorQueue.push(evt);
    uint32_t bit = error_bit(code);
    if (bit) _activeErrorMask |= bit;
    _previous = _current;
    _current  = DeviceState::ERROR;
    maskToSave = _activeErrorMask;
    xSemaphoreGive(_mutex);

    nvs_save_mask(maskToSave); // fora do mutex para não bloquear a task

    ESP_LOGE(TAG, "[%s::%s] ERRO (%s): %s",
             source.className.c_str(), source.method.c_str(),
             toString(code), msg.c_str());
}

// ---- Resolução de erro ----

void AppState::resolveError(ErrorCode code) {
    uint32_t bit = error_bit(code);
    if (!bit) return;

    uint32_t maskToSave;
    bool wasActive;

    xSemaphoreTake(_mutex, portMAX_DELAY);
    wasActive = (_activeErrorMask & bit) != 0;
    if (wasActive) {
        AppError evt;
        evt.code     = code;
        evt.resolved = true;
        _errorQueue.push(evt);
        _activeErrorMask &= ~bit;
    }
    maskToSave = _activeErrorMask;
    xSemaphoreGive(_mutex);

    if (wasActive) {
        nvs_save_mask(maskToSave);
        ESP_LOGI(TAG, "Erro [%s] marcado como resolvido — evento enfileirado.", toString(code));
    }
}

// ---- Consultas ----

bool AppState::isErrorActive(ErrorCode code) {
    uint32_t bit = error_bit(code);
    if (!bit) return false;
    xSemaphoreTake(_mutex, portMAX_DELAY);
    bool active = (_activeErrorMask & bit) != 0;
    xSemaphoreGive(_mutex);
    return active;
}

bool AppState::hasActiveErrors() {
    xSemaphoreTake(_mutex, portMAX_DELAY);
    bool any = (_activeErrorMask != 0);
    xSemaphoreGive(_mutex);
    return any;
}

bool AppState::hasQueuedErrors() {
    xSemaphoreTake(_mutex, portMAX_DELAY);
    bool has = !_errorQueue.empty();
    xSemaphoreGive(_mutex);
    return has;
}

AppError AppState::popError() {
    xSemaphoreTake(_mutex, portMAX_DELAY);
    AppError e = _errorQueue.empty() ? AppError{} : _errorQueue.front();
    if (!_errorQueue.empty()) _errorQueue.pop();
    xSemaphoreGive(_mutex);
    return e;
}

AppError AppState::peekError() {
    xSemaphoreTake(_mutex, portMAX_DELAY);
    AppError e = _errorQueue.empty() ? AppError{} : _errorQueue.front();
    xSemaphoreGive(_mutex);
    return e;
}

// ---- Leitores de estado ----

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

// ---- toString ----

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
        default:                                return "UNKNOWN";
    }
}

const char* AppState::toString(ErrorCode code) {
    switch (code) {
        case ErrorCode::NONE:                          return "NONE";
        case ErrorCode::NVS_INIT_FAIL:                 return "NVS_INIT_FAIL";
        case ErrorCode::NVS_WRITE_FAIL:                return "NVS_WRITE_FAIL";
        case ErrorCode::NVS_COMMIT_FAIL:               return "NVS_COMMIT_FAIL";
        case ErrorCode::NVS_LOAD_FAIL:                 return "NVS_LOAD_FAIL";
        case ErrorCode::WIFI_TIMEOUT:                  return "WIFI_TIMEOUT";
        case ErrorCode::TIME_SYNC_FAIL:                return "TIME_SYNC_FAIL";
        case ErrorCode::PROVISIONING_REQUEST_FAIL:     return "PROVISIONING_REQUEST_FAIL";
        case ErrorCode::PROVISIONING_RESPONSE_INVALID: return "PROVISIONING_RESPONSE_INVALID";
        case ErrorCode::MQTT_INIT_FAIL:                return "MQTT_INIT_FAIL";
        case ErrorCode::MQTT_DISCONNECTED:             return "MQTT_DISCONNECTED";
        case ErrorCode::OTA_FAIL:                      return "OTA_FAIL";
        case ErrorCode::HTTP_INIT_FAIL:                return "HTTP_INIT_FAIL";
        case ErrorCode::HTTP_REQUEST_FAIL:             return "HTTP_REQUEST_FAIL";
        case ErrorCode::MEMORY_ALOCATION_FAIL:         return "MEMORY_ALOCATION_FAIL";
        case ErrorCode::KEY_GENERATION_FAIL:           return "KEY_GENERATION_FAIL";
        case ErrorCode::CSR_SUBJECT_NAME_FAIL:         return "CSR_SUBJECT_NAME_FAIL";
        case ErrorCode::CSR_TO_PEM_FAIL:               return "CSR_TO_PEM_FAIL";
        case ErrorCode::KEY_MISSING:                   return "KEY_MISSING";
        case ErrorCode::CERT_MISSING:                  return "CERT_MISSING";
        case ErrorCode::TOPIC_SUBSCRIBE_FAIL:          return "TOPIC_SUBSCRIBE_FAIL";
        case ErrorCode::WATCHDOG_INIT_FAIL:            return "WATCHDOG_INIT_FAIL";
        case ErrorCode::WATCHDOG_ADD_FAIL:             return "WATCHDOG_ADD_FAIL";
        case ErrorCode::WATCHDOG_REMOVE_FAIL:          return "WATCHDOG_REMOVE_FAIL";
        case ErrorCode::TOPIC_FORMAT_INVALID:          return "TOPIC_FORMAT_INVALID";
        case ErrorCode::COMMAND_RESPONSE_INVALID:      return "COMMAND_RESPONSE_INVALID";
        case ErrorCode::DEVICE_ID_MISSING:             return "DEVICE_ID_MISSING";
        case ErrorCode::WIFI_CREDENTIALS_MISSING:      return "WIFI_CREDENTIALS_MISSING";
        case ErrorCode::FIRMWARE_VERSION_MISSING:      return "FIRMWARE_VERSION_MISSING";
        case ErrorCode::FIRMWARE_ROLLBACK_FAILED:      return "FIRMWARE_ROLLBACK_FAILED";
        case ErrorCode::UNKNOWN:                       return "UNKNOWN";
        default:                                       return "UNKNOWN";
    }
}
