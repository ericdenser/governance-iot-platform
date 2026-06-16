#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_sntp.h"
#include "cJSON.h"

#include "src/AppState.h"
#include "src/CryptoManager.h"
#include "src/WifiManager.h"
#include "src/WatchdogManager.h"
#include "src/MqttManager.h"
#include "src/PayloadManager.h"
#include "src/OtaManager.h"
#include "src/CommandProcessor.h"

#include "pb_encode.h"
#include "device_status.pb.h"
#include "device_error.pb.h"

// =============================================================================
//  Configurações
// =============================================================================
#define MAX_WIFI_RETRIES        10
#define MAX_CRASH_COUNT         3 // Maximum allowed crashes before forcing a rollback
#define URL_PROVISIONING        "http://172.16.39.40:8082/api/provisioning/activate"
#define URL_ERROR_REPORT        "http://172.16.39.40:8082/api/provisioning/error"
#define ADVERTISE_INTERVAL_MS   1000

//ppgeec 172.16.39.40
// alencar 192.168.15.76

static const char* TAG           = "MAIN";
static const char* NVS_NAMESPACE = "main_store";
static bool valid_firmware       = false;

// =============================================================================
//  Macros de log contextuais — prefixam automaticamente o estado atual
// =============================================================================
#define SLOG_I(fmt, ...) ESP_LOGI(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)
#define SLOG_W(fmt, ...) ESP_LOGW(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)
#define SLOG_E(fmt, ...) ESP_LOGE(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)

// =============================================================================
//  variavies
// =============================================================================
static std::string        g_macAddress;
static std::string        g_deviceId;
static std::string        g_msgOut;
static int64_t            g_lastAdvertiseMs = 0;
static esp_reset_reason_t g_reset_reason; // last reset reason
static std::string        firmware_version;
static int                crashCount = 0;
static bool hasBrokerConnection;

// =============================================================================
//  Helpers NVS
// =============================================================================
static void nvs_read_str(const char* ns, const char* key, char* buf, size_t len) {
    nvs_handle_t h;
    buf[0] = '\0';
    if (nvs_open(ns, NVS_READONLY, &h) != ESP_OK) return;
    size_t sz = len;
    nvs_get_str(h, key, buf, &sz);
    nvs_close(h);
}



// =============================================================================
//  nanopb — callback de encoding para strings e helper de publish
// =============================================================================
static bool encode_string(pb_ostream_t* stream, const pb_field_t* field, void* const* arg) {
    const char* str = (const char*)(*arg);
    if (!str) str = "";
    return pb_encode_tag_for_field(stream, field) &&
           pb_encode_string(stream, (const uint8_t*)str, strlen(str));
}

static void publish_proto_error(const char* topic,
                                const char* device_id,
                                const char* mac,
                                const char* fw_ver,
                                const char* ssid,
                                uint32_t    error_code,
                                const char* error_msg,
                                const char* error_source,
                                bool        resolved,
                                const char* extra = nullptr) {
    uint8_t proto_buf[512] = {};
    DeviceError msg = DeviceError_init_zero;

    msg.device_id.funcs.encode    = encode_string; msg.device_id.arg    = (void*)device_id;
    msg.mac.funcs.encode          = encode_string; msg.mac.arg          = (void*)mac;
    msg.fw_version.funcs.encode   = encode_string; msg.fw_version.arg   = (void*)fw_ver;
    msg.ssid.funcs.encode         = encode_string; msg.ssid.arg         = (void*)ssid;
    msg.error_code                = error_code;
    msg.error_msg.funcs.encode    = encode_string; msg.error_msg.arg    = (void*)error_msg;
    msg.error_source.funcs.encode = encode_string; msg.error_source.arg = (void*)error_source;
    msg.resolved                  = resolved;
    if (extra) {
        msg.extra.funcs.encode = encode_string; msg.extra.arg = (void*)extra;
    }

    pb_ostream_t os = pb_ostream_from_buffer(proto_buf, sizeof(proto_buf));
    if (!pb_encode(&os, DeviceError_fields, &msg)) {
        ESP_LOGE(TAG, "pb_encode DeviceError falhou: %s", PB_GET_ERROR(&os));
        return;
    }
    MqttManager::publish(proto_buf, os.bytes_written, topic, 1);
}

static void publish_proto_status(const char* topic,
                                const char* mac,
                                const char* fw_ver,
                                const char* ssid,
                                uint32_t state,
                                const char* detail = nullptr) {

    uint8_t proto_buf[256] = {};
    DeviceStatus msg = DeviceStatus_init_zero;

    msg.mac.funcs.encode          = encode_string; msg.mac.arg          = (void*)mac;
    msg.fw_version.funcs.encode   = encode_string; msg.fw_version.arg   = (void*)fw_ver;
    msg.ssid.funcs.encode         = encode_string; msg.ssid.arg         = (void*)ssid;
    msg.state                  = state;
    if (detail) {
        msg.detail.funcs.encode = encode_string; msg.detail.arg  = (void*)detail;
    }

    pb_ostream_t os = pb_ostream_from_buffer(proto_buf, sizeof(proto_buf));
    if (!pb_encode(&os, DeviceStatus_fields, &msg)) {
        ESP_LOGE(TAG, "pb_encode falhou: %s", PB_GET_ERROR(&os));
        return;
    }
    MqttManager::publish(proto_buf, os.bytes_written, topic);
}






// =============================================================================
//  Handlers
// =============================================================================

// ---- NVS_INIT --------------------------------[TRANSICIONA PRA WIFI]--------------------
static void handle_nvs_init() {
    SLOG_I("Inicializando NVS flash...");

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        SLOG_W("NVS precisou ser apagada (motivo: %s). Reinicializando...", esp_err_to_name(err));
        nvs_flash_erase();
        err = nvs_flash_init();
    }

    if (err != ESP_OK) {
        SLOG_E("Falha ao inicializar NVS: %s", esp_err_to_name(err));
        AppState::setError(ErrorCode::NVS_INIT_FAIL, esp_err_to_name(err), {TAG, "handle_nvs_init"});
        return;
    }

    // NVS pronta — restaura o bitmask de erros ativos que sobreviveu ao reboot
    AppState::loadPersistedErrors();

    SLOG_I("NVS inicializada com sucesso.");

    // Lê dados salvos para decidir o próximo estado
    SLOG_I("Verificando configurações do Firmware.");
    char saved_ssid[64]  = {0};
    char saved_token[64] = {0};
    char saved_device_id[64] = {0}; 

    char fw_buf[32] = {0};
    nvs_read_str(NVS_NAMESPACE,    "fw_version", fw_buf,          sizeof(fw_buf));
    firmware_version = fw_buf;
    nvs_read_str("crypto_store", "wifi_ssid",  saved_ssid,  sizeof(saved_ssid));
    nvs_read_str("crypto_store", "prov_token", saved_token, sizeof(saved_token));
    nvs_read_str("crypto_store", "device_id", saved_device_id, sizeof(saved_device_id));

    g_deviceId = std::string(saved_device_id);

    bool hasWifi       = (saved_ssid[0] != '\0');
    bool hasToken      = (saved_token[0] != '\0');
    bool isProvisioned = CryptoManager::isProvisioned();
    bool hasDeviceId = (saved_device_id[0] != '\0');

    SLOG_I("Diagnóstico NVS — WiFi salvo: %s | Token salvo: %s | Certificado: %s | DeviceId %s | Version %s",
           hasWifi       ? "SIM" : "NÃO",
           hasToken      ? "SIM" : "NÃO",
           isProvisioned ? "SIM" : "NÃO",
           hasDeviceId   ? "SIM" :  "NÃO",
           firmware_version.c_str());

    
    if (!hasDeviceId) {
        SLOG_E("Device_ID nao encontrado na NVS. Pacote de flash invalido.");
        AppState::setError(ErrorCode::DEVICE_ID_MISSING, "device_id ausente na NVS", {TAG, "handle_nvs_init"});
        return;
    }

    if (!hasWifi) {
        SLOG_W("Credenciais de wifi não encontradas na NVS.");
        AppState::setError(ErrorCode::WIFI_CREDENTIALS_MISSING, "WiFi credentials ausente na NVS", {TAG, "handle_nvs_init"});
        return;
    }

    if (!isProvisioned) {
        SLOG_W("Certificado não encontrado na NVS.");
        AppState::setError(ErrorCode::CERT_MISSING, "Certificado do ESP ausente na NVS", {TAG, "handle_nvs_init"});
        return;

    } 

    if (firmware_version.empty()) {
        SLOG_W("Versao nao encontrada na NVS.");
        AppState::setError(ErrorCode::FIRMWARE_VERSION_MISSING, "Firmware Version not found in NVS", {TAG, "handle_nvs_init"});
        return;
    }

    SLOG_I(">>>>>>>>>>>>> FIRMWARE VERSION [v%s] <<<<<<<<<<<<<<<", firmware_version.c_str());
    SLOG_I("Configuração NVS pronta. Conectando ao WiFi salvo: [%s]", saved_ssid);
    AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_nvs_init"});
}

// ---- WIFI_CONNECTING -------------------------[TRANSICIONA PRA TIME_SYNC]---------------
static void handle_wifi_connecting() {
    char saved_ssid[64] = {0};
    char saved_pass[64] = {0};
    nvs_read_str("crypto_store", "wifi_ssid", saved_ssid, sizeof(saved_ssid));
    nvs_read_str("crypto_store", "wifi_pass", saved_pass, sizeof(saved_pass));

    SLOG_I("Tentando conectar à rede: [%s] (máx %d tentativas)", saved_ssid, MAX_WIFI_RETRIES);

    static bool is_wifi_inited = false; // Flag para saber se já demos init

    if (!is_wifi_inited) {
        WifiConfig cfg;
        cfg.ssid        = saved_ssid;
        cfg.password    = saved_pass;
        cfg.max_retries = MAX_WIFI_RETRIES;
        WifiManager::init(cfg);
        is_wifi_inited = true;
    } else {
        // Se já inicializou antes, pede para limpar os erros e tentar de novo
        WifiManager::reconnect(); 
    }

    if (!WifiManager::waitForConnection(WatchdogManager::reset)) {
        SLOG_E("Timeout ao conectar em [%s] após %d tentativas.", saved_ssid, MAX_WIFI_RETRIES);
        AppState::setError(ErrorCode::WIFI_TIMEOUT,
                            "Não foi possível conectar em " + std::string(saved_ssid), 
                            {TAG, "handle_wifi_connecting"},
                            {
                                {"failed_ssid", saved_ssid},
                                {"reason", WifiManager::failReasonToString(WifiManager::getFailReason())},
                            }
                            );
        return;
    }

    g_macAddress = WifiManager::getMacAddress();

    SLOG_I("WiFi conectado com sucesso!");
    SLOG_I("IP: %s | MAC: %s | SSID: %s | RSSI: %d dBm",
           WifiManager::getIp().c_str(),
           g_macAddress.c_str(),
           WifiManager::getSSID().c_str(),
           WifiManager::getRssi());

    // WiFi resolvido — enfileira evento de resolução se WIFI_TIMEOUT estava ativo
    AppState::resolveError(ErrorCode::WIFI_TIMEOUT);

    // Conectou no wifi com sucesso e leu as configurações da nvs, valida firmware
    if (!valid_firmware) {
        OtaManager::set_valid_version();
        valid_firmware = true;
    }

    AppState::transition(DeviceState::TIME_SYNC, {TAG, "handle_wifi_connecting"});
}

// ---- TIME_SYNC -------------------------------[TRANSICIONA PRA MQTT]---------------------
static void handle_time_sync() {
    SLOG_I("Iniciando sincronização NTP (servidor: pool.ntp.org)...");
    esp_sntp_setoperatingmode(SNTP_OPMODE_POLL);
    esp_sntp_setservername(0, "pool.ntp.org");
    esp_sntp_init();
    int retry = 2;
    while (sntp_get_sync_status() == SNTP_SYNC_STATUS_RESET && retry < 2) {
        SLOG_I("Aguardando resposta NTP... (%d/15)", retry + 1);
        vTaskDelay(pdMS_TO_TICKS(2000));
        WatchdogManager::reset();
        retry++;
    }
    if (sntp_get_sync_status() == SNTP_SYNC_STATUS_RESET) {
        SLOG_W("NTP não respondeu após %d tentativas. Continuando sem sincronização.", retry);
    } else {
        time_t now;
        struct tm timeinfo;
        time(&now);
        localtime_r(&now, &timeinfo);
        SLOG_I("Relógio sincronizado: %s", asctime(&timeinfo));
    }

    AppState::transition(DeviceState::MQTT_INIT, {TAG, "handle_time_sync"});

}

// ---- MQTT_CONNECTING -------------------------[TRANSICIONA PRA HANDLE_ROLLBACK]----------
static void handle_mqtt_connecting() {

    MqttManager::init_mqtt();

    // Definição do que acontece quando chegar algo no tópico
    MqttManager::setCallback([](const std::string& topic, const std::string& payload) {
        ESP_LOGI(TAG, "Subscriber callback recebeu o payload %s do tópico: %s", payload.c_str(), topic.c_str());

        bool success = CommandProcessor::manage(payload);

        // Se der erro, tratamos
        if (!success) {
            SLOG_E("Command Processor falhou.");
            //AppState::CommandFailed
        }
    });

    // Espera conexão com o broker ser concluida, o MqttManager faz a transition para seguir fluxo
    AppState::transition(DeviceState::MQTT_WAITING_CONNECT, {TAG, "handle_mqtt_connecting"});
}

// ---- ROLLBACK/OTA_SUCCESSFUL/CRASH_DETECT ----[TRANSICIONA PRA OPERATIONAL]--------------
static void handle_boot_audit() {

    // Se chegou aqui, mqtt conectou com sucesso. Subscribe no topico 
    std::string topicCmd = "commands/" + g_deviceId + "/#";
    MqttManager::subscribe(topicCmd, 1);

    char topic[128];
    snprintf(topic, sizeof(topic), "status/%s", g_deviceId.c_str());
    std::string ssid = WifiManager::getSSID();

    nvs_handle_t otaHandler;
    int8_t otaNotified = 1; // default seguro
    if (nvs_open("ota_store", NVS_READWRITE, &otaHandler) == ESP_OK) {

        nvs_get_i8(otaHandler, "ota_notified", &otaNotified);

        // OTA executado com sucesso — notifica MDM e segue para operação
        if (!otaNotified) {
            AppState::transition(DeviceState::OTA_SUCCESSFUL, {TAG, "handle_boot_audit"});
            SLOG_I("OTA_SUCCESSFUL detectado, postando status: [OTA_SUCCESSFUL].");
            publish_proto_status(topic, g_macAddress.c_str(),
                                 firmware_version.c_str(), ssid.c_str(),
                                 (uint32_t)AppState::get());

            nvs_set_i8(otaHandler, "ota_notified", 1);
            nvs_commit(otaHandler);
            nvs_close(otaHandler);

            // Se acabou de fazer OTA, nao tem necessidade de verificarmos ROLLBACK nem CRASH COUNT
            AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_boot_audit"});
            return;
        } else {
            SLOG_I("Nenhum OTA detectado para notificação.");
        }

    }
    

    // Não fez OTA
    // Checar motivo ultimo reset --------------
    
    nvs_handle_t mainHandler;
    if (nvs_open("main_store", NVS_READWRITE, &mainHandler) == ESP_OK) {

        g_reset_reason = esp_reset_reason();
        SLOG_I("VERIFICANDO MOTIVO DO ULTIMO RESET:");
        if (g_reset_reason == ESP_RST_WDT || 
            g_reset_reason == ESP_RST_TASK_WDT || 
            g_reset_reason == ESP_RST_PANIC) {
            SLOG_W("Crash por falha detectado, incrementando.");

            int8_t saved = 0;
            nvs_get_i8(mainHandler, "crash_count", &saved);
            crashCount = saved + 1;
            nvs_set_i8(mainHandler, "crash_count", crashCount);
            nvs_commit(mainHandler);
            SLOG_W("Crash count: [%d].", crashCount);
        } else {
            SLOG_W("Nenhum crash detectado, zerando crash_count.");
            nvs_set_i8(mainHandler, "crash_count", 0);
            nvs_commit(mainHandler);
        }

        if(crashCount >= MAX_CRASH_COUNT) {
            SLOG_E("LIMITE DE CRASH ATINGIDO, SEGUINDO COM FLUXO DE INVALIDAÇÃO DE FIRMWARE");
            nvs_set_i8(mainHandler, "crash_count", 0);
            nvs_commit(mainHandler);
            std::string reason = "crashCount";
            OtaManager::set_invalid_version(reason);
        }
        nvs_close(mainHandler);
    } else {
        ESP_LOGE(TAG, "Nao foi possível abrir nvs (main_store)");
    }
    
    // VERIFICAR ROLLBACK --------------------------
    // A chave "target_ver" só é criada pela sua função OtaManager::verify_and_update
    std::string lastTarget;
    std::string invalidVer;
    std::string rollback_msg;
    char reason[24] = {0};

    char lastTargetBuf[32] = {0};
    nvs_read_str("ota_store", "target_ver", lastTargetBuf, sizeof(lastTargetBuf));
    lastTarget = lastTargetBuf;

    if (!lastTarget.empty() && OtaManager::verify_rollback(rollback_msg, invalidVer)) {

        SLOG_W("Rollback detectado. Versao invalida: %s. Notificando MDM...", invalidVer.c_str());

        char fw_buf[32] = {0};
        nvs_read_str("main_store", "fw_version", fw_buf, sizeof(fw_buf));
        firmware_version = fw_buf;
        nvs_read_str("ota_store", "rollbackReason", reason, sizeof(reason));

        char detail[192];
        snprintf(detail, sizeof(detail), "{\"invalid_ver\":\"%s\",\"reason\":\"%s\"}", invalidVer.c_str(), reason);

        AppState::transition(DeviceState::FIRMWARE_ROLLBACK, {TAG, "verify_rollback"});

        publish_proto_status(topic, g_macAddress.c_str(),
                             firmware_version.c_str(), ssid.c_str(),
                             (uint32_t)AppState::get(), detail);
    } else if (!lastTarget.empty()) {
        // OTA foi iniciado mas o download foi interrompido (ex: WDT crash).
        // esp_ota_get_last_invalid_partition() retorna NULL porque a partição
        // nunca foi marcada como inválida.
        // Reportamos o evento para o MDM.
        SLOG_W("OTA v%s abortado (download incompleto). Notificando MDM...", lastTarget.c_str());

        char fw_buf2[32] = {0};
        nvs_read_str("main_store", "fw_version", fw_buf2, sizeof(fw_buf2));
        firmware_version = fw_buf2;

        AppState::setError(
           ErrorCode::OTA_FAIL,
            "Error while updating to new firmware.",
            {TAG, "verify_and_update"},
            {
                {"attempted_version", lastTarget},
                {"current_version",   firmware_version}
            }
        );


        // Limpa target_ver para não reportar novamente no próximo boot
        nvs_handle_t otaWriter;
        if (nvs_open("ota_store", NVS_READWRITE, &otaWriter) == ESP_OK) {
            nvs_erase_key(otaWriter, "target_ver");
            nvs_commit(otaWriter);
            nvs_close(otaWriter);
        }
    } else {
        SLOG_I("Nenhum rollback pendente de notificação.");
    }

    // VERIFICAR SE EXECUTOU UM COMANDO DE RESET (RESTART, DEEPSLEEP, ROLLBACK)
    nvs_handle_t commandHandler;
    if (nvs_open("command_store", NVS_READWRITE, &commandHandler) == ESP_OK) {
        int8_t commandNotified = 1; // default, nada a notificar
        nvs_get_i8(commandHandler, "commandNotified", &commandNotified);
        // Se commandNotified = 0, precisa notificar mdm que foi concluido
        if (!commandNotified) {

            char commandName[32] = {0};
            size_t len = sizeof(commandName);
            nvs_get_str(commandHandler, "lastCommand", commandName, &len);

            ESP_LOGI(TAG, "Notificando comando concluido [%s] ao MDM", commandName);

            char detail[64];
            snprintf(detail, sizeof(detail), "{\"command\":\"%s\"}", commandName);
            SLOG_I("Notificando comando concluído [%s] ao MDM.", commandName);
            AppState::transition(DeviceState::COMMAND_COMPLETE, {TAG, "handle_boot_audit"});
            
            publish_proto_status(topic, g_macAddress.c_str(),
                                 firmware_version.c_str(), ssid.c_str(),
                                 (uint32_t)AppState::get(), detail);

            nvs_set_i8(commandHandler, "commandNotified", 1);
            nvs_commit(commandHandler);
        }
        nvs_close(commandHandler);
    }

    // MQTT conectado com sucesso — resolve erros de conectividade que possam estar pendentes
    AppState::resolveError(ErrorCode::MQTT_INIT_FAIL);
    AppState::resolveError(ErrorCode::MQTT_DISCONNECTED);

    // Se houver eventos na fila (erros anteriores ao boot, ou resoluções recém-geradas),
    // drena via handle_error antes de entrar em OPERATIONAL
    if (AppState::hasQueuedErrors()) {
        AppState::transition(DeviceState::ERROR, {TAG, "handle_boot_audit"});
    } else {
        AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_boot_audit"});
    }
}

// ---- PROVISIONING SUCCESS ------------------------------------------------------------
static void handle_operational() {

    int64_t now = esp_timer_get_time() / 10000; 
    

    // TODO: FLUXO DE LER SENSOR, ENVIAR DADOS PRO TOPICO TELEMETRIA E STATUS PRO TOPICO STATUS
    // Avisa periodicamente que esta pronto para operação
    if (now - g_lastAdvertiseMs >= ADVERTISE_INTERVAL_MS) {
        g_lastAdvertiseMs = now;

        if (!MqttManager::isConnected()) {
            vTaskDelay(pdMS_TO_TICKS(500));
            return;
        }

        std::string ssid = WifiManager::getSSID();
        char topic[64];
        snprintf(topic, sizeof(topic), "status/%s", g_deviceId.c_str());

        SLOG_I("Publicando advertise. Tópico: [%s]", topic);

        publish_proto_status(topic, g_macAddress.c_str(), firmware_version.c_str(), ssid.c_str(), (uint8_t)AppState::get());
    }

    vTaskDelay(pdMS_TO_TICKS(500));
}

// ---- ERROR ------------------------------------------------------------------
static void handle_error() {
    SLOG_E("Entrando no handler de erro. Eventos na fila: %s",
           AppState::hasQueuedErrors() ? "SIM" : "NÃO");

    if (!AppState::hasQueuedErrors()) {
        // Fila vazia mas estado ERROR , retorna pra operação
        AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_error"});
        return;
    }

    // Sem IP = sem WiFi = sem MQTT: aguarda e tenta reconectar pelo fluxo normal.
    // O resolveError(WIFI_TIMEOUT) será chamado quando o WiFi reconectar em handle_wifi_connecting.
    if (WifiManager::getIp().empty()) {
        SLOG_W("Sem conectividade. Aguardando 10s antes de tentar reconectar WiFi...");
        vTaskDelay(pdMS_TO_TICKS(10000));
        AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_error"});
        return;
    }

    // WiFi ok mas MQTT desconectado — dispara reconexão e aguarda.
    // Se reconectar, MQTT_EVENT_CONNECTED transita de volta para esta função com fila pronta para drenar.
    if (!MqttManager::isConnected()) {
        SLOG_W("MQTT desconectado. Aguardando reconexão...");
        MqttManager::tryReconnect();
        vTaskDelay(pdMS_TO_TICKS(15000));
        return;
    }

    // WiFi e MQTT disponíveis — drena toda a fila publicando cada evento em ordem FIFO
    char topicErr[128];
    snprintf(topicErr, sizeof(topicErr), "error/%s", g_deviceId.c_str());
    std::string ssid = WifiManager::getSSID();

    while (AppState::hasQueuedErrors()) {
        AppError e = AppState::popError();

        std::string src_str = e.source.className + "::" + e.source.method;
        std::string details_str;
        if (!e.details.empty()) {
            details_str = "{";
            for (auto it = e.details.begin(); it != e.details.end(); ++it) {
                details_str += "\"" + it->first + "\": \"" + it->second + "\"";
                if (std::next(it) != e.details.end()) details_str += ", ";
            }
            details_str += "}";
        }

        if (e.resolved) {
            SLOG_I("Publicando resolução de [%s]", AppState::toString(e.code));
        } else {
            SLOG_E("Publicando erro [%s]: %s | origem: %s | details: %s",
                   AppState::toString(e.code), e.msg.c_str(),
                   src_str.c_str(), details_str.c_str());
        }

        publish_proto_error(topicErr,
                            g_deviceId.c_str(),
                            g_macAddress.c_str(),
                            firmware_version.c_str(),
                            ssid.c_str(),
                            (uint32_t)e.code,
                            e.msg.c_str(),
                            src_str.c_str(),
                            e.resolved,
                            e.details.empty() ? nullptr : details_str.c_str());
    }

    // Fila drenada — decide próximo estado com base nos erros ainda ativos
    if (AppState::hasActiveErrors()) {
        // Há erros sem resolução confirmada: aguarda instrução do MDM
        SLOG_W("Erros ativos sem resolução. Aguardando instrução do MDM...");
        //AppState::transition(DeviceState::WAITING_RESPONSE, {TAG, "handle_error"});
        AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_error"});
    } else {
        // Todos os erros foram resolvidos
        SLOG_I("Todos os erros resolvidos. Retomando operação.");
        AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_error"});
    }
}

// TODO: WAITING INSTRUCTION SER UMA ESPECIE DE "ESPERANDO CALLBACK DO MDM VIA BROKER"
static void handle_waiting_instruction() {
    SLOG_W("Waiting instruction...");
    while(1){
        WatchdogManager::reset();
        vTaskDelay(pdMS_TO_TICKS(5000));
    };
}


// =============================================================================
//  app_main 
// =============================================================================
extern "C" void app_main(void) {
    AppState::init();
    WatchdogManager::init(60000, true);
    WatchdogManager::addToCurrentTask();

    while (true) {
        WatchdogManager::reset();

        switch (AppState::get()) {
            case DeviceState::NVS_INIT:                  handle_nvs_init();             break;
            case DeviceState::WIFI_CONNECTING:           handle_wifi_connecting();      break;
            case DeviceState::TIME_SYNC:                 handle_time_sync();            break;
            case DeviceState::MQTT_INIT:                 handle_mqtt_connecting();      break;
            case DeviceState::MQTT_WAITING_CONNECT:      vTaskDelay(pdMS_TO_TICKS(5000)); break;
            case DeviceState::ERROR:                     handle_error();                break;
            case DeviceState::BOOT_AUDIT:                handle_boot_audit();           break;
            case DeviceState::OTA_FOUND:                                                break;
            case DeviceState::OTA_DOWNLOADING:                                          break;
            case DeviceState::WAITING_RESPONSE:          handle_waiting_instruction();  break;
            case DeviceState::OPERATIONAL:               handle_operational();          break;
            case DeviceState::FIRMWARE_ROLLBACK:                                        break;
            case DeviceState::OTA_SUCCESSFUL: break;
            case DeviceState::HTTP_INIT: break;
            case DeviceState::HTTP_REQUEST: break;
            case DeviceState::REBOOTING: break;
            case DeviceState::PROVISIONING_SUCCESS: break;
            case DeviceState::PROVISIONING: break;
            case DeviceState::WIFI_AP_MODE: break;
            case DeviceState::COMMAND_COMPLETE: break;

        }
    }
}

