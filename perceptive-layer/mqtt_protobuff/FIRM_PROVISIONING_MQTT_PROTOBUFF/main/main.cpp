#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_sntp.h"
#include "i2cdev.h"
#include "ds3231.h"

#include "src/AppState.h"
#include "src/GpsManager.h"
#include "src/CryptoManager.h"
#include "src/WifiManager.h"
#include "src/HttpService.h"
#include "src/WatchdogManager.h"
#include "src/MqttManager.h"
#include "src/CaptivePortal.h"
#include "src/OtaManager.h"
#include "src/CommandProcessor.h"
#include "PayloadManager.h"

#include "driver/uart.h"
#include "pb_encode.h"
#include "device_status.pb.h"
#include "device_error.pb.h"

// =============================================================================
//  Configurações
// =============================================================================
#define MAX_WIFI_RETRIES        10
#define MAX_CRASH_COUNT         3 // Maximum allowed crashes before forcing a rollback
#define URL_PROVISIONING        CONFIG_GOV_MDM_BASE_URL "provisioning/activate"
#define ADVERTISE_INTERVAL_MS   10000

#define GPS_UART_PORT    UART_NUM_1
#define GPS_UART_RX_PIN  GPIO_NUM_17
#define GPS_UART_TX_PIN  GPIO_NUM_16
#define GPS_POWER_PIN    GPIO_NUM_18  // NPN base (via 1kΩ) → corta VCC do GPS pelo PNP

static const char* TAG           = "MAIN";
static const char* NVS_NAMESPACE = "main_store";
static bool valid_firmware       = false;
static bool time_synced          = false;

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

PayloadManager json;
PayloadManager params;
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
    msg.timestamp                 = (uint64_t)time(NULL);
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

    msg.mac.funcs.encode       = encode_string; msg.mac.arg       = (void*)mac;
    msg.fw_version.funcs.encode = encode_string; msg.fw_version.arg = (void*)fw_ver;
    msg.ssid.funcs.encode      = encode_string; msg.ssid.arg      = (void*)ssid;
    msg.state                  = state;
    msg.timestamp              = (uint64_t)time(NULL);
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
    SLOG_I("Iniciando sincronização de horário (GPS → NTP → MDM)...");

    struct tm timeinfo = {};

    // Helper local: grava struct tm no DS3231 (pula silenciosamente se não detectado)
    auto writeToRTC = [&](const char* source) {
        // Probe rápido via i2c_master_probe — retorno imediato, sem retry loop
        i2c_dev_t probe = {};
        probe.port               = I2C_NUM_0;
        probe.addr               = 0x68;
        probe.cfg.sda_io_num     = GPIO_NUM_8;
        probe.cfg.scl_io_num     = GPIO_NUM_9;
        probe.cfg.sda_pullup_en  = true;
        probe.cfg.scl_pullup_en  = true;
        probe.cfg.master.clk_speed = 400000;
        i2c_dev_create_mutex(&probe);
        bool ds3231Present = (i2c_dev_probe(&probe, I2C_DEV_WRITE) == ESP_OK);
        i2c_dev_delete_mutex(&probe);

        if (!ds3231Present) {
            SLOG_W("DS3231 não detectado — gravação de horário ignorada.");
            return;
        }

        i2c_dev_t rtc_dev = {};
        if (ds3231_init_desc(&rtc_dev, I2C_NUM_0, GPIO_NUM_8, GPIO_NUM_9) == ESP_OK) {
            rtc_dev.cfg.sda_pullup_en    = true;
            rtc_dev.cfg.scl_pullup_en    = true;
            rtc_dev.cfg.master.clk_speed = 400000;
            if (ds3231_set_time(&rtc_dev, &timeinfo) == ESP_OK) {
                SLOG_I("DS3231 atualizado com horário %s.", source);
            } else {
                SLOG_W("Falha ao gravar horário %s no DS3231.", source);
            }
            ds3231_free_desc(&rtc_dev);
        } else {
            SLOG_E("Falha ao inicializar descritor DS3231.");
        }
    };

    // --- 1. GPS (mais preciso, não depende de rede) ---------------------------
    SLOG_I("Ligando GPS (GPIO%d)...", GPS_POWER_PIN);
    gpio_set_level(GPS_POWER_PIN, 1);
    vTaskDelay(pdMS_TO_TICKS(500)); // aguarda módulo inicializar

    // Probe rápido: instala UART, tenta ler sentença NMEA por ~2s.
    // Evita bloquear 90s quando nenhum GPS está conectado.
    bool gpsPresent = false;
    {
        uart_config_t probe_cfg = {
            .baud_rate  = 9600,
            .data_bits  = UART_DATA_8_BITS,
            .parity     = UART_PARITY_DISABLE,
            .stop_bits  = UART_STOP_BITS_1,
            .flow_ctrl  = UART_HW_FLOWCTRL_DISABLE,
            .source_clk = UART_SCLK_DEFAULT,
        };
        if (uart_driver_install(GPS_UART_PORT, 256, 0, 0, NULL, 0) == ESP_OK) {
            uart_param_config(GPS_UART_PORT, &probe_cfg);
            uart_set_pin(GPS_UART_PORT, GPS_UART_TX_PIN, GPS_UART_RX_PIN,
                         UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
            uint8_t buf[64];
            for (int i = 0; i < 10 && !gpsPresent; i++) {
                int len = uart_read_bytes(GPS_UART_PORT, buf, sizeof(buf), pdMS_TO_TICKS(200));
                for (int j = 0; j < len; j++) {
                    if (buf[j] == '$') { gpsPresent = true; break; }
                }
            }
            uart_driver_delete(GPS_UART_PORT);
        }
    }

    if (!gpsPresent) {
        SLOG_W("GPS não detectado na UART%d — pulando espera de fix.", GPS_UART_PORT);
        gpio_set_level(GPS_POWER_PIN, 0);
    } else {
        SLOG_I("GPS detectado. Aguardando fix (timeout 90s — cold start)...");
        GpsFix gpsFix = {};
        if (GpsManager::waitForFix(GPS_UART_TX_PIN, GPS_UART_RX_PIN, GPS_UART_PORT, gpsFix, 90000)) {
            timeinfo          = gpsFix.time;
            timeinfo.tm_isdst = 0;
            time_t epoch = mktime(&timeinfo);
            struct timeval tv = { .tv_sec = epoch, .tv_usec = 0 };
            settimeofday(&tv, nullptr);
            SLOG_I("Horário obtido do GPS: %04d-%02d-%02d %02d:%02d:%02d UTC",
                   timeinfo.tm_year + 1900, timeinfo.tm_mon + 1, timeinfo.tm_mday,
                   timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
            writeToRTC("GPS");
            time_synced = true;
        } else {
            SLOG_W("GPS sem fix após 90s. Tentando NTP...");
        }
        gpio_set_level(GPS_POWER_PIN, 0);
        SLOG_I("GPS desligado.");
    }

    // --- 2. NTP (fallback via rede) -------------------------------------------
    if (!time_synced) {
        esp_sntp_setoperatingmode(SNTP_OPMODE_POLL);
        esp_sntp_setservername(0, "pool.ntp.org");
        esp_sntp_init();
        int retry = 0;
        while (sntp_get_sync_status() == SNTP_SYNC_STATUS_RESET && retry < 3) {
            SLOG_I("Aguardando resposta NTP... (%d/3)", retry + 1);
            vTaskDelay(pdMS_TO_TICKS(2000));
            WatchdogManager::reset();
            retry++;
        }
        if (sntp_get_sync_status() != SNTP_SYNC_STATUS_RESET) {
            time_t now;
            time(&now);
            localtime_r(&now, &timeinfo);
            SLOG_I("Horário sincronizado via NTP: %s", asctime(&timeinfo));
            writeToRTC("NTP");
            time_synced = true;
        } else {
            SLOG_W("NTP não respondeu após %d tentativas. Fallback para timestamp do MDM.", retry);
            // TODO: AppState::setError(ErrorCode::TIME_SYNC_FAIL, "NTP timeout", {TAG, "handle_time_sync"});
        }
    }

    // --- 3. MDM timestamp: fallback final tratado em CryptoManager::handleProvisioningResponse

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
    bool saved = CryptoManager::handleProvisioningResponse(responseBuffer, g_msgOut, time_synced);
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

    int64_t now = esp_timer_get_time() / 1000;

    // Avisa periodicamente que esta pronto para operação
    if (now - g_lastAdvertiseMs >= ADVERTISE_INTERVAL_MS) {
        g_lastAdvertiseMs = now;


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
        AppState::transition(DeviceState::PROVISIONING_SUCCESS, {TAG, "handle_error"});
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
        AppState::transition(DeviceState::PROVISIONING_SUCCESS, {TAG, "handle_error"});
    } else {
        // Todos os erros foram resolvidos
        SLOG_I("Todos os erros resolvidos. Retomando operação.");
        AppState::transition(DeviceState::PROVISIONING_SUCCESS, {TAG, "handle_error"});
    }
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
            publish_proto_status(topic, g_macAddress.c_str(),
                                 firmware_version.c_str(), ssid.c_str(),
                                 (uint32_t)AppState::get());
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

        g_reset_reason = esp_reset_reason();
        SLOG_I("VERIFICANDO MOTIVO DO ULTIMO RESET:");
        if (g_reset_reason == ESP_RST_WDT || 
            g_reset_reason == ESP_RST_TASK_WDT || 
            g_reset_reason == ESP_RST_PANIC) {
            int8_t saved = 0;
            nvs_get_i8(mainHandler, "crash_count", &saved);
            crashCount = saved + 1;
            nvs_set_i8(mainHandler, "crash_count", crashCount);
            nvs_commit(mainHandler);
            SLOG_W("Crash por falha detectado, incrementando.");
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
    // A chave "target_ver" só é criada pela função OtaManager::verify_and_update
    char target_ver_buf[32] = {0};
    nvs_read_str("ota_store", "target_ver", target_ver_buf, sizeof(target_ver_buf));
    std::string lastTarget = target_ver_buf;

    std::string invalidVer;
    std::string rollback_msg;
    char reason[24] = {0};

    if (!lastTarget.empty() && OtaManager::verify_rollback(rollback_msg, invalidVer)) {

        SLOG_W("Rollback detectado. Versao invalida: %s. Notificando MDM...", invalidVer.c_str());

        nvs_read_str("ota_store", "rollbackReason", reason, sizeof(reason));

        char detail[64];
        snprintf(detail, sizeof(detail), "ROLLBACK:v%s:%s", invalidVer.c_str(), reason);

        publish_proto_status(topic, g_macAddress.c_str(),
                             firmware_version.c_str(), ssid.c_str(),
                             (uint32_t)AppState::get(), detail);
    } else if (!lastTarget.empty()) {
        // OTA foi iniciado mas o download foi interrompido (ex: WDT crash).
        // esp_ota_get_last_invalid_partition() retorna NULL porque a partição
        // nunca foi marcada como inválida.
        SLOG_W("OTA v%s abortado (download incompleto). Notificando MDM...", lastTarget.c_str());

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
            snprintf(detail, sizeof(detail), "COMMAND_COMPLETE:%s", commandName);
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
    i2cdev_init();

    // GPIO de controle do transistor PNP que liga/desliga VCC do GPS
    gpio_reset_pin(GPS_POWER_PIN);
    gpio_set_direction(GPS_POWER_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level(GPS_POWER_PIN, 0); // GPS começa desligado

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
            case DeviceState::HTTP_REQUEST:
            case DeviceState::OTA_SUCCESSFUL:                                 break;
            case DeviceState::FIRMWARE_ROLLBACK: break;
            case DeviceState::COMMAND_COMPLETE: break;
        }
    }
}

