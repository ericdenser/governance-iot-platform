#ifndef APPSTATE_H
#define APPSTATE_H

#include <string>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"


//  Estados do ciclo de vida do device
enum class DeviceState {
    BOOT,               // Primeiro tick — nada inicializado ainda
    NVS_INIT,           // Inicializando flash/NVS
    WIFI_AP_MODE,       // SoftAP ativo, aguardando usuário configurar via captive portal
    WIFI_CONNECTING,    // Tentando conectar à rede WiFi salva
    TIME_SYNC,          // Sincronizando relógio via NTP (necessário para mTLS)
    PROVISIONING,       // Enviando CSR ao MDM para obter certificado
    MQTT_WAITING_CONNECT,    
    MQTT_INIT,          // Inicializando cliente MQTT com mTLS
    OPERATIONAL,        // Loop principal — telemetria, status, OTA checks
    OTA_FOUND,          // Timer do ota 
    OTA_DOWNLOADING,    // Download + flash de firmware em progresso
    REBOOTING,          // Aguardando reboot controlado
    ERROR,               // Erro recuperável — log + notificação + reboot suave
    HTTP_INIT,
    HTTP_REQUEST
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
    UNKNOWN
};



struct Source {
    std::string className;
    std::string method;
};
 
struct AppError {
    ErrorCode code    = ErrorCode::NONE;
    std::string msg;    // Descrição legível
    Source source;     // Info do módulo que gerou o erro (ex: "CryptoManager", func "handleProvisioning")
};


// =============================================================================
//  AppState
//
//  USO
//    Sucesso:   AppState::transition(DeviceState::MQTT_CONNECTING, "MqttManager");
//    Erro:      AppState::setError(ErrorCode::MQTT_INIT_FAIL, "client init retornou NULL", {"MqttManager", "metodoX");
// =============================================================================
class AppState {
public:

    // Inicializa o mutex — chamar UMA VEZ em app_main antes de tudo
    static void init();

    // Faz a transição para um novo estado com log automático
    static void transition(DeviceState next, const Source& source);

    // Leitura do estado atual e anterior
    static DeviceState get();
    static DeviceState previous();
    static bool is(DeviceState state);

    // Registra um erro e transiciona automaticamente para ERROR
    static void setError(ErrorCode code, const std::string& msg, const Source& source);
    static AppError getError();
    static void clearError();

    // Converte enum para string legível nos logs
    static const char* toString(DeviceState state);
    static const char* toString(ErrorCode code);

private:
    static DeviceState      _current;
    static DeviceState      _previous;
    static AppError         _lastError;
    static SemaphoreHandle_t _mutex;
};

#endif