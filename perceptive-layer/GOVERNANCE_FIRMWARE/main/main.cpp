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
#define FIRMWARE_VERSION        3
#define PASSWORD                "mackleaps"
#define MAX_WIFI_RETRIES        10
#define URL_PROVISIONING        "http://192.168.15.64:8082/api/provisioning/activate"
#define URL_ERROR_REPORT        "http://192.168.15.64:8082/api/provisioning/error"
#define TELEMETRY_INTERVAL_MS   7000

static const char* TAG = "MAIN";

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
static std::string  g_msgOut;
static int64_t      g_lastTelemetryMs = 0;

static PayloadManager json;

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

// ---- BOOT -------------------------------------------------------------------
static void handle_boot() {
    SLOG_I("Device iniciando. Firmware v%d", FIRMWARE_VERSION);
    AppState::transition(DeviceState::NVS_INIT, {TAG, "handle_boot"});
}

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

    // Lê dados salvos para decidir o próximo estado
    char saved_ssid[64]  = {0};
    char saved_token[64] = {0};
    nvs_read_str("crypto_store", "wifi_ssid",  saved_ssid,  sizeof(saved_ssid));
    nvs_read_str("crypto_store", "prov_token", saved_token, sizeof(saved_token));

    bool hasWifi       = (saved_ssid[0] != '\0');
    bool hasToken      = (saved_token[0] != '\0');
    bool isProvisioned = CryptoManager::isProvisioned();

    SLOG_I("Diagnóstico NVS — WiFi salvo: %s | Token salvo: %s | Certificado: %s",
           hasWifi       ? "SIM" : "NÃO",
           hasToken      ? "SIM" : "NÃO",
           isProvisioned ? "SIM" : "NÃO");

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

    WifiConfig cfg;
    cfg.ssid        = saved_ssid;
    cfg.password    = saved_pass;
    cfg.max_retries = MAX_WIFI_RETRIES;
    WifiManager::init(cfg);

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

    AppState::transition(DeviceState::TIME_SYNC, {TAG, "handle_wifi_connecting"});
}

// ---- TIME_SYNC --------------------------------------------------------------
// ---- TIME_SYNC --------------------------------------------------------------
static void handle_time_sync() {
    SLOG_I("Iniciando sincronização NTP (servidor: pool.ntp.org)...");

    esp_sntp_setoperatingmode(SNTP_OPMODE_POLL);
    esp_sntp_setservername(0, "pool.ntp.org");
    esp_sntp_init();

    setenv("TZ", "BRT3", 1); 
    tzset();

    int retry = 0;
    time_t now = 0;
    struct tm timeinfo = {0};

    // Em vez de checar o status do SNTP, checamos se o ano atual passou de 2024
    while (retry < 15) {
        time(&now);
        localtime_r(&now, &timeinfo);
        
        // tm_year conta os anos desde 1900. Portanto, 124 = 2024.
        if (timeinfo.tm_year >= 124) {
            break; // O tempo sincronizou perfeitamente!
        }
        
        SLOG_I("Aguardando resposta NTP... (%d/15)", retry + 1);
        vTaskDelay(pdMS_TO_TICKS(2000));
        WatchdogManager::reset();
        retry++;
    }

    // Se saiu do loop e ainda estamos em 1970, deu erro crítico.
    if (timeinfo.tm_year < 124) {
        SLOG_E("Falha crítica: NTP não sincronizou. A conexao mTLS será recusada.");
        // Usamos o WIFI_TIMEOUT pois o seu handle_error já tem lógica 
        // para dar um delay e tentar conectar de novo sem reiniciar o ESP.
        AppState::setError(ErrorCode::WIFI_TIMEOUT, 
                           "Falha ao obter tempo da internet", 
                           {TAG, "handle_time_sync"});
        return; 
    }

    SLOG_I("Relógio sincronizado: %s", asctime(&timeinfo));

    bool isProvisioned = CryptoManager::isProvisioned();
    SLOG_I("Certificado mTLS presente: %s. Próximo estado: %s",
           isProvisioned ? "SIM" : "NÃO",
           isProvisioned ? "MQTT_INIT" : "PROVISIONING");

    if (!isProvisioned) {
        AppState::transition(DeviceState::PROVISIONING, {TAG, "handle_time_sync"});
    } else {
        AppState::transition(DeviceState::MQTT_INIT, {TAG, "handle_time_sync"});
    }
}

// ---- PROVISIONING -----------------------------------------------------------
static void handle_provisioning() {
    AppState::transition(DeviceState::PROVISIONING, {TAG, "handle_provisioning"});

    char saved_token[64] = {0};
    nvs_read_str("crypto_store", "prov_token", saved_token, sizeof(saved_token));

    SLOG_I("Iniciando provisionamento. Token: [%.8s...] MAC: [%s]",
           saved_token, g_macAddress.c_str());

    // Gera par de chaves ECC + CSR
    SLOG_I("Gerando par de chaves ECC e CSR via mbedTLS...");
    std::string espCert = CryptoManager::handleProvisioning(g_macAddress, saved_token);


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

    // Execução não chega aqui — handleProvisioningResponse chama esp_restart()
    SLOG_I("Certificado salvo. Reiniciando para aplicar...");
}

// ---- MQTT_CONNECTING --------------------------------------------------------
static void handle_mqtt_connecting() {

    SLOG_I("Carregando certificado mTLS da NVS e iniciando cliente MQTT...");

    MqttManager::setCallback([](const std::string& topic, const std::string& payload) {
        ESP_LOGI(TAG, "Processador Central recebeu o comando %s do tópico: %s", topic.c_str(), payload.c_str());
        
  
        bool success = CommandProcessor::manage(topic, payload);
        if (!success) {
            SLOG_E("Command Processor falhou.");
        }
    });
    

    MqttManager::init_mqtt();

    AppState::transition(DeviceState::MQTT_WAITING_CONNECT, {TAG, "handle_mqtt_connecting"});

    std::string mac = WifiManager::getMacAddress();
    std::string topicCmd = "commands/" + mac + "/#";
}

// ---- OPERATIONAL ------------------------------------------------------------
static void handle_operational() {


    static bool isSubscribed = false;
    if (!isSubscribed) {
        std::string topicCmd = "commands/" + g_macAddress + "/#";
        MqttManager::subscribe(topicCmd, 1);
        isSubscribed = true;
    }
    
    int64_t now = esp_timer_get_time() / 1000; 

    // Telemetria periódica
    if (now - g_lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
        g_lastTelemetryMs = now;

        std::string ssid = WifiManager::getSSID();
        std::string ip   = WifiManager::getIp();
        int rssi         = WifiManager::getRssi();

        json.add("mac",              g_macAddress);
        json.add("firmware_version", FIRMWARE_VERSION);
        json.add("ssid",             ssid);
        json.add("ip",               ip);
        json.add("rssi",             rssi);

        const char* payload = json.build();

        char topic[64];
        snprintf(topic, sizeof(topic), "telemetry/%s", g_macAddress.c_str());

        SLOG_I("Publicando telemetria. Tópico: [%s] | Payload: %s", topic, payload);
        MqttManager::publish(payload, topic);
        json.clear();
        SLOG_I("Telemetria publicada. IP: %s | RSSI: %d dBm", ip.c_str(), rssi);
    }

    vTaskDelay(pdMS_TO_TICKS(500));
}

// ---- OTA_DOWNLOADING --------------------------------------------------------
static void handle_ota() {
    SLOG_I("Verificando atualização de firmware. Versão atual: v%d", FIRMWARE_VERSION);

    nvs_handle_t nvsHandle;
    nvs_open("ota_store", NVS_READWRITE, &nvsHandle);

    // OtaManager::verify_and_update(
    //     FIRMWARE_VERSION, urlCheck, nvsHandle, g_msgOut,
    //     []() { WatchdogManager::reset(); }
    // );

    nvs_close(nvsHandle);

    // Se chegou aqui sem restart, não havia update ou houve falha não-fatal
    if (AppState::is(DeviceState::OTA_DOWNLOADING)) {
        SLOG_I("OTA finalizado sem atualização aplicada. Resultado: %s", g_msgOut.c_str());
        SLOG_I("Retornando ao loop operacional.");
        AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_ota"});
    }
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

        json.add("mac",              g_macAddress);
        json.add("firmware_version", FIRMWARE_VERSION);
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
    }

    // Política de recuperação por tipo de erro
    if (err.code == ErrorCode::WIFI_TIMEOUT) {
        SLOG_W("Erro de WiFi — tentando reconectar em 10s (sem reboot)...");
        vTaskDelay(pdMS_TO_TICKS(10000));
        AppState::clearError();
        AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_error"});
        return;
    }

    SLOG_E("Erro não recuperável. Agendando reboot...");
    AppState::transition(DeviceState::REBOOTING, {TAG, "handle_error"});
}

// ---- REBOOTING --------------------------------------------------------------
static void handle_rebooting() {
    SLOG_W("Reiniciando device em 3 segundos...");
    vTaskDelay(pdMS_TO_TICKS(3000));
    esp_restart();
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
            case DeviceState::BOOT:             handle_boot();             break;
            case DeviceState::NVS_INIT:         handle_nvs_init();         break;
            case DeviceState::WIFI_AP_MODE:     handle_wifi_ap_mode();     break;
            case DeviceState::WIFI_CONNECTING:  handle_wifi_connecting();  break;
            case DeviceState::TIME_SYNC:        handle_time_sync();        break;
            case DeviceState::PROVISIONING:     handle_provisioning();     break;
            case DeviceState::MQTT_INIT:        handle_mqtt_connecting();  break;
            case DeviceState::MQTT_WAITING_CONNECT:  vTaskDelay(pdMS_TO_TICKS(5000)); break;
            case DeviceState::OPERATIONAL:      handle_operational();      break;
            case DeviceState::OTA_FOUND:        handle_ota();              break;
            case DeviceState::ERROR:            handle_error();            break;
            case DeviceState::REBOOTING:        handle_rebooting();        break;
            case DeviceState::OTA_DOWNLOADING:                             break;
            case DeviceState::HTTP_INIT:
            case DeviceState::HTTP_REQUEST:
                        break;
        }
    }
}