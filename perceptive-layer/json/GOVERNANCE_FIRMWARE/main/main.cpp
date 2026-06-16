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
#include "src/HttpService.h"
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
#define URL_PROVISIONING        "http://192.168.15.76:8082/api/provisioning/activate"
#define URL_ERROR_REPORT        "http://192.168.15.76:8082/api/provisioning/error"
#define ADVERTISE_INTERVAL_MS   1000

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
static bool        g_rollback_detected = false;
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

// ---- NVS_INIT ---------------------------------------------------------------
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

    SLOG_I("Verificando versao do Firmware.");
    char fw_buf[32] = {0};
    nvs_read_str(NVS_NAMESPACE, "fw_version", fw_buf, sizeof(fw_buf));
    firmware_version = fw_buf;

    if (firmware_version.empty()) {
        SLOG_W("Versao nao encontrada na NVS. Firmware de provisioning.");
    } else {
        SLOG_I("Versao do firmware carregada da NVS: v%s", firmware_version.c_str());
    }


    // Lê dados salvos para decidir o próximo estado
    char saved_ssid[64]  = {0};
    char saved_token[64] = {0};
    char saved_device_id[64] = {0};

    nvs_read_str("crypto_store", "wifi_ssid",  saved_ssid,  sizeof(saved_ssid));
    nvs_read_str("crypto_store", "prov_token", saved_token, sizeof(saved_token));
    nvs_read_str("crypto_store", "device_id", saved_device_id, sizeof(saved_device_id));

    g_deviceId = std::string(saved_device_id);

    bool hasWifi       = (saved_ssid[0] != '\0');
    bool hasToken      = (saved_token[0] != '\0');
    bool isProvisioned = CryptoManager::isProvisioned();
    bool hasDeviceId = (saved_device_id[0] != '\0');

    SLOG_I("Diagnóstico NVS — WiFi salvo: %s | Token salvo: %s | Certificado: %s | DeviceId %s",
           hasWifi       ? "SIM" : "NÃO",
           hasToken      ? "SIM" : "NÃO",
           isProvisioned ? "SIM" : "NÃO",
           hasDeviceId   ? "SIM" :  "NÃO");

    
    if (!hasDeviceId) {
        SLOG_E("device_id nao encontrado na NVS. Pacote de flash invalido.");
        AppState::setError(ErrorCode::NVS_LOAD_FAIL, "device_id ausente na NVS", {TAG, "handle_provisioning"});
        return;
    }

    if (!hasWifi || (!hasToken && !isProvisioned)) {
        SLOG_W("Configuração incompleta. Subindo SoftAP para setup inicial.");
        AppState::transition(DeviceState::WIFI_AP_MODE, {TAG, "handle_nvs_init"});
    } else {
        SLOG_I("Configuração encontrada. Conectando ao WiFi salvo: [%s]", saved_ssid);
        AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_nvs_init"});
    }
}

// ---- WIFI_AP_MODE -----------------------------------------------------------
static void handle_wifi_ap_mode() {
    static bool started = false;
    if (!started) {
        SLOG_I("Primeiro boot sem configuração. Iniciando SoftAP + CaptivePortal...");

        WifiConfig ap_cfg;
        ap_cfg.ssid     = "Mackleaps-IoT-Setup";
        ap_cfg.password = "lfs123!@#";
        WifiManager::initAP(ap_cfg);
        CaptivePortal::start();
        started = true;

        SLOG_I("SoftAP ativo. SSID: [Mackleaps-IoT-Setup]. Aguardando configuração do usuário...");
        SLOG_I("Acesse http://setup.local no navegador para configurar o dispositivo.");
    }
    // Aguarda o captive portal salvar as credenciais e reiniciar o device
    vTaskDelay(pdMS_TO_TICKS(1000));
}

// ---- WIFI_CONNECTING --------------------------------------------------------
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

// ---- TIME_SYNC --------------------------------------------------------------
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

    // Verificação de próximo estado
    bool isProvisioned = CryptoManager::isProvisioned();
    SLOG_I("Certificado mTLS presente: %s. Próximo estado: %s",
           isProvisioned ? "SIM" : "NÃO",
           isProvisioned ? "MQTT_CONNECTING" : "PROVISIONING");
    if (!isProvisioned) {
        AppState::transition(DeviceState::PROVISIONING, {TAG, "handle_time_sync"});
    } else {
        AppState::transition(DeviceState::MQTT_INIT, {TAG, "handle_time_sync"});
    }
}

// ---- PROVISIONING -----------------------------------------------------------
static void handle_provisioning() {

    char saved_token[64] = {0};
    nvs_read_str("crypto_store", "prov_token", saved_token, sizeof(saved_token));

    SLOG_I("Iniciando provisionamento. Token: [%.8s...] DeviceID: [%s] MAC: [%s]",
       saved_token, g_deviceId.c_str(), g_macAddress.c_str());
    // Gera par de chaves ECC + CSR
    SLOG_I("Gerando par de chaves ECC e CSR via mbedTLS...");
    std::string espCert = CryptoManager::handleProvisioning(g_deviceId, g_macAddress, saved_token);


    // ============= ERRO NA GERACAO DO CERT OU KEY ===============
    if (espCert.empty()) {
        SLOG_E("Falha ao gerar CSR. CryptoManager retornou payload vazio.");
        return;
    }

    SLOG_I("CSR gerado com sucesso. Enviando para MDM em: %s", URL_PROVISIONING);
    SLOG_I("Payload enviado: %.80s...", espCert.c_str());

    std::string responseBuffer;
    bool ok = HttpService::post(URL_PROVISIONING, espCert, responseBuffer, g_msgOut, "application/json");

    // ============= CHECA REQUISICAO ===============
    if (!ok) {
        SLOG_E("Falha no POST para /activate");
        return;
    }

    SLOG_I("POST para /activate bem-sucedido. Resposta do MDM: %s", responseBuffer.c_str());
    SLOG_I("Processando resposta e salvando certificado na NVS...");

    // ============= CONSUMO DA RESPOSTA ===============
    bool saved = CryptoManager::handleProvisioningResponse(responseBuffer, g_msgOut);
    if (!saved) {
        SLOG_E("Falha ao processar resposta do MDM");
        return;
    }

    // Execução não chega aqui -> handleProvisioningResponse chama esp_restart()
    SLOG_I("Certificado salvo. Reiniciando para aplicar...");
}

// ---- MQTT_CONNECTING --------------------------------------------------------
static void handle_mqtt_connecting() {

    MqttManager::init_mqtt();

    MqttManager::setCallback([](const std::string& topic, const std::string& payload) {
        ESP_LOGI(TAG, "Processador Central recebeu o payload %s do tópico: %s", payload.c_str(), topic.c_str());

        bool success = CommandProcessor::manage(payload);
        if (!success) {
            SLOG_E("Command Processor falhou.");
        }
    });

    AppState::transition(DeviceState::MQTT_WAITING_CONNECT, {TAG, "handle_mqtt_connecting"});
}

// ---- PROVISIONING SUCCESS ------------------------------------------------------------
static void handle_provisioning_success() {

    int64_t now = esp_timer_get_time() / 10000; 

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
        SLOG_I("WiFi disponível. Reportando erro para: %s", URL_ERROR_REPORT);

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

        std::string responseBuffer;
        bool reportOk = HttpService::post(URL_ERROR_REPORT, payload, responseBuffer, g_msgOut, "application/json");

        if (reportOk) {
            SLOG_I("Erro reportado com sucesso ao servidor. Resposta: %s", responseBuffer.c_str());
        } else {
            SLOG_W("Falha ao reportar erro ao servidor: %s", g_msgOut.c_str());
        }
        json.clear();
    } else {
        SLOG_W("WiFi indisponível. Erro não pôde ser reportado ao servidor.");
        SLOG_E("Erro não recuperável. Agendando reboot...");
        AppState::transition(DeviceState::REBOOTING, {TAG, "handle_error"});
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

// ---- REBOOTING --------------------------------------------------------------
static void handle_rebooting() {
    SLOG_W("Reiniciando device em 3 segundos...");
    vTaskDelay(pdMS_TO_TICKS(3000));
    esp_restart();
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

            AppState::transition(DeviceState::OTA_SUCCESSFUL, {TAG, "handle_boot_audit"});
            json.add("device_id",  g_deviceId);
            json.add("mac",        g_macAddress);
            json.add("fw_version", firmware_version);
            json.add("ssid",       ssid);
            json.add("status",     AppState::toString(AppState::get()));
            MqttManager::publish(json.build(), topic);
            json.clear();

            nvs_set_i8(otaHandler, "ota_notified", 1);
            nvs_commit(otaHandler);
            nvs_close(otaHandler);

            // Se acabou de fazer OTA, nao tem necessidade de verificarmos ROLLBACK nem CRASH COUNT
            AppState::transition(DeviceState::PROVISIONING_SUCCESS, {TAG, "handle_boot_audit"});
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

    AppState::transition(DeviceState::PROVISIONING_SUCCESS, {TAG, "handle_rollback"});
}

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
    WatchdogManager::init(300000, true);
    WatchdogManager::addToCurrentTask();

    while (true) {
        WatchdogManager::reset();

        switch (AppState::get()) {
            case DeviceState::NVS_INIT:         handle_nvs_init();         break;
            case DeviceState::WIFI_AP_MODE:     handle_wifi_ap_mode();     break;
            case DeviceState::WIFI_CONNECTING:  handle_wifi_connecting();  break;
            case DeviceState::TIME_SYNC:        handle_time_sync();        break;
            case DeviceState::PROVISIONING:     handle_provisioning();     break;
            case DeviceState::PROVISIONING_SUCCESS:      handle_provisioning_success();      break;
            case DeviceState::MQTT_INIT:        handle_mqtt_connecting();  break;
            case DeviceState::MQTT_WAITING_CONNECT:  vTaskDelay(pdMS_TO_TICKS(5000)); break;
            case DeviceState::ERROR:            handle_error();            break;
            case DeviceState::REBOOTING:        handle_rebooting();        break;
            case DeviceState::BOOT_AUDIT:  handle_boot_audit();       break; 
            case DeviceState::OTA_FOUND:                                   break;
            case DeviceState::OTA_DOWNLOADING:                             break;
            case DeviceState::WAITING_RESPONSE: handle_waiting_instruction(); break;
            case DeviceState::OPERATIONAL:    
            case DeviceState::HTTP_INIT:
            case DeviceState::HTTP_REQUEST:                                 break;
            case DeviceState::OTA_SUCCESSFUL: break;
            case DeviceState::FIRMWARE_ROLLBACK: break;
        }
    }
}

