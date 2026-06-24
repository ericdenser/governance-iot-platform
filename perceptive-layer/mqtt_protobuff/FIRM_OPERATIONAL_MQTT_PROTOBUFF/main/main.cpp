#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "cJSON.h"
#include <sys/time.h>
#include "ds3231.h"
#include "i2cdev.h"

#include "src/SensorDiscovery.h"
#include "src/GpsManager.h"
#include "src/BatteryManager.h"
#include "src/AdcManager.h"

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
#include "device_telemetry.pb.h"

// =============================================================================
//  Configurações
// =============================================================================
#define MAX_WIFI_RETRIES        10
#define MAX_CRASH_COUNT         3 // Maximum allowed crashes before forcing a rollback
#define URL_PROVISIONING        "http://172.16.39.40:8082/api/provisioning/activate"
#define URL_ERROR_REPORT        "http://172.16.39.40:8082/api/provisioning/error"
#define STATUS_INTERVAL_MS      30000
#define TELEMETRY_INTERVAL_MS   5000
#define GPS_SYNC_EVERY_N_BOOTS  24   // corrige drift do DS3231 a cada ~48min (ciclos de 2min)

//ppgeec 172.16.39.40
//alencar 192.168.15.76

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
static esp_reset_reason_t g_reset_reason;
static std::string        firmware_version;
static int                crashCount = 0;

static SensorMap   g_sensors      = {};
static GpsManager  g_gpsManager;
static std::string g_activeSensors;  // CSV populado em handle_sensor_discovery

// ---- Fila de mensagens para o publisher task ---
struct MqttMessage {
    char    topic[128];
    uint8_t payload[512];
    size_t  payload_len;
};
static QueueHandle_t g_mqttQueue = NULL;

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

    msg.mac.funcs.encode          = encode_string; msg.mac.arg          = (void*)mac;
    msg.fw_version.funcs.encode   = encode_string; msg.fw_version.arg   = (void*)fw_ver;
    msg.ssid.funcs.encode         = encode_string; msg.ssid.arg         = (void*)ssid;
    msg.state                     = state;
    msg.timestamp                 = (uint64_t)time(NULL);
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
//  Tasks operacionais — criadas uma única vez ao entrar em OPERATIONAL
// =============================================================================

// ---- Task: leitura de sensores e publicação de telemetria (5 s) ---------
static void telemetry_task(void* /*pvParameters*/) {
    while (true) {
        if (!AppState::is(DeviceState::OPERATIONAL)) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        MqttMessage msg = {};
        snprintf(msg.topic, sizeof(msg.topic), "telemetry/%s", g_deviceId.c_str());

        DeviceTelemetry proto = DeviceTelemetry_init_zero;
        proto.device_id.funcs.encode = encode_string;
        proto.device_id.arg          = (void*)g_deviceId.c_str();
        proto.timestamp              = (uint64_t)time(NULL);

        pb_size_t n = 0;

        // --- GPS: latitude, longitude, altitude ---
        if (g_sensors.gps && g_gpsManager.hasFix()) {
            strncpy(proto.readings[n].key, "latitude",  SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[n].value = g_gpsManager.getLat(); n++;

            strncpy(proto.readings[n].key, "longitude", SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[n].value = g_gpsManager.getLon(); n++;

            strncpy(proto.readings[n].key, "altitude",  SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[n].value = g_gpsManager.getAlt(); n++;
        }

        // --- Bateria (mV) via ADC + divisor de tensão ---
        if (g_sensors.battery_adc) {
            strncpy(proto.readings[n].key, "battery_mv", SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[n].value = BatteryManager::readBattery(); n++;
        }

        // --- BMP280 (pressão / temperatura) ---
        if (g_sensors.bmp280) {
            // --- Temperatura (°C) ---
            strncpy(proto.readings[n].key, "temperature", SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[n].value = 1.0f; // TODO: bmp280
            n++;

            // --- Umidade (%) ---
            strncpy(proto.readings[n].key, "humidity", SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[n].value = 2.0f; // TODO: bmp280
            n++;
        }

        proto.readings_count = n;

        pb_ostream_t os = pb_ostream_from_buffer(msg.payload, sizeof(msg.payload));
        if (pb_encode(&os, DeviceTelemetry_fields, &proto)) {
            msg.payload_len = os.bytes_written;
            xQueueSend(g_mqttQueue, &msg, 0);
        } else {
            ESP_LOGE(TAG, "pb_encode DeviceTelemetry falhou: %s", PB_GET_ERROR(&os));
        }

        vTaskDelay(pdMS_TO_TICKS(TELEMETRY_INTERVAL_MS));
    }
}

// ---- Task: publicação de status do device (30 s) ------------------------
static void status_task(void* /*pvParameters*/) {
    while (true) {
        if (!AppState::is(DeviceState::OPERATIONAL)) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        MqttMessage msg = {};
        snprintf(msg.topic, sizeof(msg.topic), "status/%s", g_deviceId.c_str());

        std::string ssid = WifiManager::getSSID();

        DeviceStatus proto = DeviceStatus_init_zero;
        proto.mac.funcs.encode        = encode_string; proto.mac.arg        = (void*)g_macAddress.c_str();
        proto.fw_version.funcs.encode = encode_string; proto.fw_version.arg = (void*)firmware_version.c_str();
        proto.ssid.funcs.encode       = encode_string; proto.ssid.arg       = (void*)ssid.c_str();
        proto.state                   = (uint32_t)AppState::get();
        proto.timestamp               = (uint64_t)time(NULL);
        if (!g_activeSensors.empty()) {
            proto.active_sensors.funcs.encode = encode_string;
            proto.active_sensors.arg          = (void*)g_activeSensors.c_str();
        }

        pb_ostream_t os = pb_ostream_from_buffer(msg.payload, sizeof(msg.payload));
        if (pb_encode(&os, DeviceStatus_fields, &proto)) {
            msg.payload_len = os.bytes_written;
            xQueueSend(g_mqttQueue, &msg, 0);
        } else {
            ESP_LOGE(TAG, "pb_encode DeviceStatus falhou: %s", PB_GET_ERROR(&os));
        }

        vTaskDelay(pdMS_TO_TICKS(STATUS_INTERVAL_MS));
    }
}

// ---- Task: drena a fila e publica no broker (bloqueante) ----------------
static void mqtt_publisher_task(void* /*pvParameters*/) {
    MqttMessage msg;
    while (true) {
        if (xQueueReceive(g_mqttQueue, &msg, pdMS_TO_TICKS(5000))) {
            if (AppState::is(DeviceState::OPERATIONAL) && MqttManager::isConnected()) {
                MqttManager::publish(msg.payload, msg.payload_len, msg.topic);
            }
            // Se desconectado ou fora de OPERATIONAL: descarta. O status/telemetria
            // não tem garantia de entrega (QoS 0) — o próximo ciclo vai reenviar.
        }
    }
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
    SLOG_I("Iniciando sincronização de horário (DS3231 → GPS a cada %d boots)...", GPS_SYNC_EVERY_N_BOOTS);

    struct tm timeinfo = {};
    bool synced        = false;

    // Probe rápido via i2c_master_probe para ver se ds3231 está disponivel
    auto probeDS3231 = [&]() -> bool {
        i2c_dev_t probe = {};
        probe.port               = I2C_NUM_0;
        probe.addr               = 0x68;
        probe.cfg.sda_io_num     = GPIO_NUM_8;
        probe.cfg.scl_io_num     = GPIO_NUM_9;
        probe.cfg.sda_pullup_en  = true;
        probe.cfg.scl_pullup_en  = true;
        probe.cfg.master.clk_speed = 400000;
        i2c_dev_create_mutex(&probe);
        bool present = (i2c_dev_probe(&probe, I2C_DEV_WRITE) == ESP_OK);
        i2c_dev_delete_mutex(&probe);
        return present;
    };

    // Helper: grava timeinfo no DS3231 (pula se não detectado)
    auto writeToRTC = [&](const char* source) {
        if (!probeDS3231()) {
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

    // --- 1. DS3231: fonte primária 
    if (probeDS3231()) {
        i2c_dev_t rtc_dev = {};
        if (ds3231_init_desc(&rtc_dev, I2C_NUM_0, GPIO_NUM_8, GPIO_NUM_9) == ESP_OK) {
            rtc_dev.cfg.sda_pullup_en    = true;
            rtc_dev.cfg.scl_pullup_en    = true;
            rtc_dev.cfg.master.clk_speed = 400000;
            if (ds3231_get_time(&rtc_dev, &timeinfo) == ESP_OK && timeinfo.tm_year >= 124) {
                timeinfo.tm_isdst = 0;
                time_t epoch = mktime(&timeinfo);
                struct timeval tv = { .tv_sec = epoch, .tv_usec = 0 };
                settimeofday(&tv, nullptr);
                SLOG_I("Horário restaurado do DS3231: %04d-%02d-%02d %02d:%02d:%02d",
                       timeinfo.tm_year + 1900, timeinfo.tm_mon + 1, timeinfo.tm_mday,
                       timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
                synced = true;
            } else {
                SLOG_W("DS3231 sem horário válido. Tentando GPS...");
            }
            ds3231_free_desc(&rtc_dev);
        }
    } else {
        SLOG_W("DS3231 não detectado. Tentando GPS...");
    }

    // --- 2. GPS: corrige drift do DS3231 a cada N boots OU sem sincronização ---
    int32_t boot_count = 0;
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        nvs_get_i32(h, "boot_count", &boot_count);
        boot_count++;
        nvs_set_i32(h, "boot_count", boot_count);
        nvs_commit(h);
        nvs_close(h);
    }
    bool gps_sync_due = (boot_count % GPS_SYNC_EVERY_N_BOOTS == 0);

    if (!synced || gps_sync_due) {
        if (gps_sync_due && synced) {
            SLOG_I("Boot #%ld — ciclo periódico de correção GPS do DS3231.", boot_count);
        }
        SLOG_I("Ligando GPS (GPIO%d)...", GPS_POWER_PIN);
        gpio_set_level(GPS_POWER_PIN, 1);
        vTaskDelay(pdMS_TO_TICKS(500)); // aguarda módulo inicializar

        SLOG_I("Tentando obter horário e posição do GPS (timeout 8s)...");
        GpsFix gpsFix = {};
        if (GpsManager::waitForFix(GPS_UART_TX_PIN, GPS_UART_RX_PIN, GPS_UART_PORT, gpsFix, 8000)) {
            timeinfo          = gpsFix.time;
            timeinfo.tm_isdst = 0;
            time_t epoch = mktime(&timeinfo);
            struct timeval tv = { .tv_sec = epoch, .tv_usec = 0 };
            settimeofday(&tv, nullptr);
            SLOG_I("Horário obtido do GPS: %04d-%02d-%02d %02d:%02d:%02d UTC",
                   timeinfo.tm_year + 1900, timeinfo.tm_mon + 1, timeinfo.tm_mday,
                   timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
            writeToRTC("GPS");
            synced = true;
        } else {
            SLOG_W("GPS sem fix. %s", synced ? "Mantendo horário do DS3231." : "Sem fonte de tempo disponível.");
            // TODO: AppState::setError(ErrorCode::TIME_SYNC_FAIL, "GPS timeout no ciclo de correção", {TAG, "handle_time_sync"});
        }

        gpio_set_level(GPS_POWER_PIN, 0); // desliga GPS 
        SLOG_I("GPS desligado após time_sync.");
    }

    if (!synced) {
        SLOG_W("Nenhuma fonte de tempo disponível. Continuando sem sincronização.");
    }

    AppState::transition(DeviceState::MQTT_INIT, {TAG, "handle_time_sync"});
}

// ---- SENSORS_INIT ----------------------------[TRANSICIONA PRA OPERATIONAL]--------------
static void handle_sensor_discovery() {
    SLOG_I("Iniciando descoberta de sensores...");

    // Liga GPS para que o probe de UART funcione
    gpio_set_level(GPS_POWER_PIN, 1);
    vTaskDelay(pdMS_TO_TICKS(500)); // aguarda módulo inicializar

    SensorDiscovery::run(g_sensors, &g_gpsManager);

    g_activeSensors.clear();
    auto appendSensor = [](const char* name) {
        if (!g_activeSensors.empty()) g_activeSensors += ',';
        g_activeSensors += name;
    };
    if (g_sensors.bmp280)       appendSensor("bmp280");
    if (g_sensors.ds3231)       appendSensor("ds3231");
    if (g_sensors.gps)          appendSensor("gps");
    if (g_sensors.battery_adc)  appendSensor("battery_adc");

    SLOG_I("Mapa de sensores — BMP280:%s | DS3231:%s | GPS:%s | BAT_ADC:%s",
           g_sensors.bmp280      ? "SIM" : "NÃO",
           g_sensors.ds3231      ? "SIM" : "NÃO",
           g_sensors.gps         ? "SIM" : "NÃO",
           g_sensors.battery_adc ? "SIM" : "NÃO");

    if (!g_sensors.gps) {
        gpio_set_level(GPS_POWER_PIN, 0); // GPS não detectado: desliga para poupar energia
        SLOG_W("GPS não detectado. Pino de energia desligado.");
    } else {
        SLOG_I("GPS detectado. Mantendo ligado para a task contínua.");
    }

    AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_sensor_discovery"});
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
        AppState::transition(DeviceState::SENSORS_INIT, {TAG, "handle_boot_audit"});
    }
}

// ---- OPERATIONAL --------------------------------------------------------
static void handle_operational() {
    static bool started = false;
    if (!started) {
        g_mqttQueue = xQueueCreate(10, sizeof(MqttMessage));
        xTaskCreate(telemetry_task,      "telemetry", 4096, NULL, 4, NULL);
        xTaskCreate(status_task,         "status",    4096, NULL, 4, NULL);
        xTaskCreate(mqtt_publisher_task, "mqtt_pub",  4096, NULL, 5, NULL);
        if (g_sensors.gps) {
            xTaskCreate(GpsManager::taskWrapper, "gps", 4096, &g_gpsManager, 3, NULL);
        }
        started = true;
        SLOG_I("Tasks operacionais iniciadas (telemetria %ds / status %ds) | GPS:%s BAT:%s BMP280:%s",
               TELEMETRY_INTERVAL_MS / 1000, STATUS_INTERVAL_MS / 1000,
               g_sensors.gps         ? "SIM" : "NÃO",
               g_sensors.battery_adc ? "SIM" : "NÃO",
               g_sensors.bmp280      ? "SIM" : "NÃO");
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
    i2cdev_init();

    // GPIO de controle do transistor PNP que liga/desliga VCC do GPS
    gpio_reset_pin(GPS_POWER_PIN);
    gpio_set_direction(GPS_POWER_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level(GPS_POWER_PIN, 0); // GPS começa desligado

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
            case DeviceState::SENSORS_INIT:              handle_sensor_discovery();     break;
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

