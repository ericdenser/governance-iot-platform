#ifndef APPSTATE_H
#define APPSTATE_H

#include <string>
#include <queue>
#include <map>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"


//  Estados do ciclo de vida do device
enum class DeviceState {
    NVS_INIT,           // Inicializando flash/NVS
    WIFI_AP_MODE,       // SoftAP ativo, aguardando usuário configurar via captive portal
    WIFI_CONNECTING,    // Tentando conectar à rede WiFi salva
    TIME_SYNC,          // Sincronizando relógio via NTP (necessário para mTLS)
    PROVISIONING,       // Enviando CSR ao MDM para obter certificado
    PROVISIONING_SUCCESS, // Pronto para receber o firmware definitivo
    MQTT_WAITING_CONNECT,
    MQTT_INIT,          // Inicializando cliente MQTT com mTLS
    OPERATIONAL,        // Loop principal — telemetria, status, OTA checks
    OTA_FOUND,
    OTA_DOWNLOADING,    // Download + flash de firmware em progresso
    OTA_SUCCESSFUL,
    BOOT_AUDIT,
    FIRMWARE_ROLLBACK,
    REBOOTING,          // Aguardando reboot controlado
    ERROR,              // Erro — log + notificação + recovery
    HTTP_INIT,
    HTTP_REQUEST,
    WAITING_RESPONSE,
    COMMAND_COMPLETE
};

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
    UNKNOWN
};

struct Source {
    std::string className;
    std::string method;
};

struct AppError {
    ErrorCode   code     = ErrorCode::NONE;
    std::string msg;
    Source      source;
    std::map<std::string, std::string> details;
    bool        resolved = false; // true = evento de resolução do erro
};


// =============================================================================
//  AppState
//
//  USO
//    Sucesso:   AppState::transition(DeviceState::MQTT_CONNECTING, "MqttManager");
//    Erro:      AppState::setError(ErrorCode::MQTT_INIT_FAIL, "client init retornou NULL", {"MqttManager", "metodoX"});
//    Resolução: AppState::resolveError(ErrorCode::WIFI_TIMEOUT);  // só enfileira se o erro estava ativo
// =============================================================================
class AppState {
public:

    // Inicializa o mutex — chamar UMA VEZ em app_main antes de tudo
    static void init();

    // Restaura activeErrorMask do NVS — chamar após nvs_flash_init() ter sucesso
    static void loadPersistedErrors();

    // Faz a transição para um novo estado com log automático
    static void transition(DeviceState next, const Source& source);

    // Leitura do estado atual e anterior
    static DeviceState get();
    static DeviceState previous();
    static bool        is(DeviceState state);

    // Registra um erro e transiciona automaticamente para ERROR
    static void setError(ErrorCode code, const std::string& msg, const Source& source,
                         const std::map<std::string, std::string>& details = {});

    // Enfileira um evento de resolução se o erro estiver marcado como ativo
    static void resolveError(ErrorCode code);

    // Verifica se um código de erro específico está pendente de resolução
    static bool isErrorActive(ErrorCode code);

    // Verifica se há algum código de erro ainda não resolvido
    static bool hasActiveErrors();

    // Fila de eventos de erro a serem publicados
    static bool     hasQueuedErrors();
    static AppError popError();   // remove e retorna o front
    static AppError peekError();  // lê sem remover

    // Converte enum para string legível nos logs
    static const char* toString(DeviceState state);
    static const char* toString(ErrorCode code);

private:
    static DeviceState        _current;
    static DeviceState        _previous;
    static SemaphoreHandle_t  _mutex;

    static std::queue<AppError> _errorQueue;
    // Bitmask de códigos de erro ativos (persistido no NVS para sobreviver reboots).
    // Bit i corresponde a (ErrorCode)i. NONE=0 não usa bit.
    static uint32_t             _activeErrorMask;
};

#endif
