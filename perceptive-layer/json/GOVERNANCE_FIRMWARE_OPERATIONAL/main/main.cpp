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
#include "src/CaptivePortal.h"
#include "src/OtaManager.h"
#include "src/CommandProcessor.h"


// =============================================================================
//  Configurações
// =============================================================================
#define MAX_WIFI_RETRIES        10
#define MAX_CRASH_COUNT 3 // Maximum allowed crashes before forcing a rollback
#define ADVERTISE_INTERVAL_MS   1000

//ppgeec 172.16.39.40
// alencar 192.168.15.76

static const char* TAG = "MAIN";
static const char* NVS_NAMESPACE = "main_store";
static bool valid_firmware = false;

// =============================================================================
//  Macros de log contextuais — prefixam automaticamente o estado atual
// =============================================================================
#define SLOG_I(fmt, ...) ESP_LOGI(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)
#define SLOG_W(fmt, ...) ESP_LOGW(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)
#define SLOG_E(fmt, ...) ESP_LOGE(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)

// =============================================================================
//  variavies
// =============================================================================
static std::string  g_macAddress;
static std::string  g_deviceId;
static std::string  g_msgOut;
static int64_t      g_lastAdvertiseMs = 0;
static std::string g_rollback_msg;
static esp_reset_reason_t reason; // last reset reason
static std::string firmware_version;
static int crashCount = 0;

static PayloadManager json;
static PayloadManager params;

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

    SLOG_I("NVS inicializada com sucesso.");

    // Lê dados salvos para decidir o próximo estado
    SLOG_I("Verificando configurações do Firmware.");
    char saved_ssid[64]  = {0};
    char saved_token[64] = {0};
    char saved_device_id[64] = {0}; 

    char fw_buf[32] = {0};
    nvs_read_str(NVS_NAMESPACE, "fw_version", fw_buf, sizeof(fw_buf));
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
                            "Não foi possível conectar em " + std::string(saved_ssid), {TAG, "handle_wifi_connecting"});
        return;
    }

    g_macAddress = WifiManager::getMacAddress();

    SLOG_I("WiFi conectado com sucesso!");
    SLOG_I("IP: %s | MAC: %s | SSID: %s | RSSI: %d dBm",
           WifiManager::getIp().c_str(),
           g_macAddress.c_str(),
           WifiManager::getSSID().c_str(),
           WifiManager::getRssi());

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

        // OTA anterior validado com sucesso — notifica MDM e segue para operação
        if (!otaNotified) {
            SLOG_I("OTA_SUCCESSFUL detectado, postando status: [OTA_SUCCESSFUL].");
            json.add("device_id",  g_deviceId);
            json.add("mac",        g_macAddress);
            json.add("fw_version", firmware_version);
            json.add("ssid",       ssid);
            json.add("status",     "OTA_SUCCESSFUL");
            MqttManager::publish(json.build(), topic);
            json.clear();

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

        reason = esp_reset_reason();
        SLOG_I("VERIFICANDO MOTIVO DO ULTIMO RESET:");
        if (reason == ESP_RST_WDT || reason == ESP_RST_TASK_WDT || reason == ESP_RST_PANIC) {
            int8_t saved = 0;
            nvs_get_i8(mainHandler, "crash_count", &saved);
            crashCount = saved + 1;
            nvs_set_i8(mainHandler, "crash_count", crashCount);
            SLOG_W("Crash por falha detectado, incrementando.");
            nvs_commit(mainHandler);
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
        ESP_LOGW(TAG, "Nao foi possível abrir nvs (main_store)");
    }

    
    // VERIFICAR ROLLBACK --------------------------
    // A chave "target_ver" só é criada pela função OtaManager::verify_and_update
    char target_ver_buf[32] = {0};
    nvs_read_str("ota_store", "target_ver", target_ver_buf, sizeof(target_ver_buf));
    std::string lastTarget = target_ver_buf;

    std::string invalidVer;
    char reason[24] = {0};

    if (!lastTarget.empty() && OtaManager::verify_rollback(g_rollback_msg, invalidVer)) {

        SLOG_W("Rollback detectado. Versao invalida: %s. Notificando MDM...", invalidVer.c_str());

        nvs_read_str("ota_store", "rollbackReason", reason, sizeof(reason));

        params.add("invalid_ver", invalidVer);
        params.add("reason", reason);

        json.add("device_id",   g_deviceId);
        json.add("mac",         g_macAddress);
        json.add("fw_version",  firmware_version);
        json.add("ssid",        ssid);
        json.add("status",      AppState::toString(AppState::get()));
        json.addObject("params", params.getString());
        MqttManager::publish(json.build(), topic);

        json.clear();
        params.clear();
    } else if (!lastTarget.empty()) {
        // OTA foi iniciado mas o download foi interrompido (ex: WDT crash).
        // esp_ota_get_last_invalid_partition() retorna NULL porque a partição
        // nunca foi marcada como inválida.
        SLOG_W("OTA v%s abortado (download incompleto). Notificando MDM...", lastTarget.c_str());

        params.add("invalid_ver", lastTarget);
        params.add("reason", "mid_download_crash");

        json.add("device_id",  g_deviceId);
        json.add("mac",        g_macAddress);
        json.add("fw_version", firmware_version);
        json.add("ssid",       ssid);
        json.add("status",     "OTA_FAILED");
        json.addObject("params", params.getString());
        MqttManager::publish(json.build(), topic);

        json.clear();
        params.clear();

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

            params.add("command_name", commandName);

            json.add("device_id",  g_deviceId);
            json.add("mac",        g_macAddress);
            json.add("fw_version", firmware_version);
            json.add("ssid",       ssid);
            json.add("status",     "COMMAND_COMPLETE");
            json.addObject("params", params.getString());
            MqttManager::publish(json.build(), topic);

            json.clear();
            params.clear();

            commandNotified = 1;
            nvs_set_i8(commandHandler, "commandNotified", commandNotified);
            nvs_commit(commandHandler);
        }
        nvs_close(commandHandler);
    }

    AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_rollback"});
}

// ---- PROVISIONING SUCCESS ------------------------------------------------------------
static void handle_operational() {

    int64_t now = esp_timer_get_time() / 10000; 
    

    // TODO: FLUXO DE LER SENSOR, ENVIAR DADOS PRO TOPICO TELEMETRIA E STATUS PRO TOPICO STATUS
    // Avisa periodicamente que esta pronto para operação
    if (now - g_lastAdvertiseMs >= ADVERTISE_INTERVAL_MS) {
        g_lastAdvertiseMs = now;

        std::string ssid = WifiManager::getSSID();
        std::string ip   = WifiManager::getIp();
        std::string currentState = AppState::toString(AppState::get());

        json.add("device_id",              g_deviceId);
        json.add("mac",              g_macAddress);
        json.add("fw_version", firmware_version);
        json.add("ssid",             ssid);
        json.add("status",           currentState);

        const char* payload = json.build();

        char topic[64];
        snprintf(topic, sizeof(topic), "status/%s", g_deviceId.c_str());

        SLOG_I("Publicando advertise. Tópico: [%s] | Payload: %s", topic, payload);
        MqttManager::publish(payload, topic);
        json.clear();
        SLOG_I("Advertise publicado: status -> %s", currentState.c_str());
    }

    vTaskDelay(pdMS_TO_TICKS(500));
}

// ---- ERROR ------------------------------------------------------------------
// TODO: desenvolver agente especifico para tratar erros e avisar mdm futuramente
static void handle_error() {
    AppError err = AppState::getError();

    SLOG_E("Entrando no handler de erro.");
    SLOG_E("Origem: [%s -> %s] | Código: [%s] | Mensagem: %s",
           err.source.className.c_str(),
           err.source.method.c_str(),
           AppState::toString(err.code),
           err.msg.c_str());

    // Reporta o erro via HTTP se tiver conectividade
    if (WifiManager::isConnected()) {
        //SLOG_I("WiFi disponível. Reportando erro para: %s", URL_ERROR_REPORT);

        json.add("device_id",         g_deviceId);
        json.add("mac",              g_macAddress);
        json.add("fw_version", firmware_version);
        json.add("ip", WifiManager::getIp());
        json.add("ssid", WifiManager::getSSID());
        json.add("current_process", AppState::toString(AppState::get()));
        json.add("error_code",       AppState::toString(err.code));
        json.add("error_msg",        err.msg);
        json.add("error_source", err.source.className + "::" + err.source.method);

        const char* payload = json.build();
        SLOG_I("Payload de erro: %s", payload);

        // TODO: publish num topico de erro

        // if (reportOk) {
        //     SLOG_I("Erro reportado com sucesso ao servidor. Resposta: %s", responseBuffer.c_str());
        // } else {
        //     SLOG_W("Falha ao reportar erro ao servidor: %s", g_msgOut.c_str());
        // }
        json.clear();
    } else {
        SLOG_W("WiFi indisponível. Erro não pôde ser reportado ao servidor.");

        // TODO: pensar numa estrategia melhor de tratamento sem wifi
        // SLOG_E("Erro não recuperável. Agendando reboot...");
        // AppState::transition(DeviceState::REBOOTING, {TAG, "handle_error"});
    }

    // Política de recuperação por tipo de erro
    if (err.code == ErrorCode::WIFI_TIMEOUT) {
        SLOG_W("Erro de WiFi — tentando reconectar em 10s (sem reboot)...");
        vTaskDelay(pdMS_TO_TICKS(10000));
        AppState::clearError();
        AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_error"});
        return;
    }

    AppState::transition(DeviceState::WAITING_RESPONSE, {TAG, "handle_error"});
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
        }
    }
}

