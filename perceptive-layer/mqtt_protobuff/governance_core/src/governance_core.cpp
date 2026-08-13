#include "governance_core.h"

#include <string.h>
#include <string>
#include <sys/time.h>
#include <time.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"

#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_sleep.h"

#include "AppState.h"
#include "AuthManager.h"
#include "WifiManager.h"
#include "WatchdogManager.h"
#include "MqttManager.h"
#include "PayloadManager.h"
#include "OtaManager.h"
#include "CommandProcessor.h"

#include "pb_encode.h"
#include "device_status.pb.h"
#include "device_error.pb.h"
#include "device_telemetry.pb.h"

// =============================================================================
//  Configurações (Kconfig — menu Governance IoT)
// =============================================================================
#define MAX_WIFI_RETRIES        CONFIG_GOV_MAX_WIFI_RETRIES
#define MAX_CRASH_COUNT         CONFIG_GOV_MAX_CRASH_COUNT
#define STATUS_INTERVAL_MS      CONFIG_GOV_STATUS_INTERVAL_MS
#define TELEMETRY_INTERVAL_MS   CONFIG_GOV_TELEMETRY_INTERVAL_MS

static const char* TAG           = "GOV_CORE";
static const char* NVS_NAMESPACE = "main_store";
static const char* NVS_KEY_HIBERNATION_REASON = "hib_reason";

#if defined(CONFIG_GOV_FIRMWARE_DEEP_SLEEP) || defined (CONFIG_GOV_BATTERY_HIBERNATION)
static const char* HIBERNATION_REASON_LOW_BATTERY = "LOW_BATTERY";
static const char* HIBERNATION_REASON_DEEP_SLEEP = "DEEP_SLEEP";
static const char* HIBERNATION_REASON_TIMEOUT = "TIMEOUT_SLEEP";
#endif

// =============================================================================
//  Macros de log contextuais
// =============================================================================
#define SLOG_I(fmt, ...) ESP_LOGI(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)
#define SLOG_W(fmt, ...) ESP_LOGW(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)
#define SLOG_E(fmt, ...) ESP_LOGE(TAG, "PROCESS -> %s: " fmt, AppState::toString(AppState::get()), ##__VA_ARGS__)

// =============================================================================
//  State compartilhado
// =============================================================================
static std::string        g_macAddress;
static std::string        g_deviceId;
static esp_reset_reason_t g_reset_reason;
static std::string        firmware_version;
static int                crashCount = 0;
static bool               valid_firmware = false;
static std::string        g_activeSensors;   // populado via hook sensor_discovery()
static governance_hooks_t s_hooks = {};

#if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
static bool                telemetryPublished = false;
static bool                statusPublished = false;
#endif

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
//  nanopb helpers
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
        msg.detail.funcs.encode = encode_string; msg.detail.arg = (void*)detail;
    }

    pb_ostream_t os = pb_ostream_from_buffer(proto_buf, sizeof(proto_buf));
    if (!pb_encode(&os, DeviceStatus_fields, &msg)) {
        ESP_LOGE(TAG, "pb_encode falhou: %s", PB_GET_ERROR(&os));
        return;
    }
    MqttManager::publish(proto_buf, os.bytes_written, topic);
}


#if defined(CONFIG_GOV_FIRMWARE_DEEP_SLEEP) || defined(CONFIG_GOV_BATTERY_HIBERNATION)
    // Persiste motivo do ultimo deep_sleep. Usado no boot para distinguir 
    static void set_hibernation_reason(const char* reason) {
        nvs_handle_t h;
        if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) return;
        if (reason == nullptr) {
            nvs_erase_key(h, NVS_KEY_HIBERNATION_REASON);
        } else {
            nvs_set_str(h, NVS_KEY_HIBERNATION_REASON, reason);
        }
        nvs_commit(h);
        nvs_close(h);
    }

    // Lê motivo do ultimo deep_sleep
    static void get_hibernation_reason(char* buf, size_t len) {
        buf[0] = '\0';
        nvs_handle_t h;
        if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) return;
        size_t sz = len;
        nvs_get_str(h, NVS_KEY_HIBERNATION_REASON, buf, &sz);
        nvs_close(h);
    }

    static void enter_deep_sleep(uint64_t wake_interval_s, const char* reason) {
        

        SLOG_W("Preparando deep sleep por %llus (motivo: %s)", wake_interval_s, reason);

        //Desliga peripherals externos que o firmware controla via GPIO
        // #ifdef CONFIG_GOV_GPS_POWER_PIN
        // gpio_set_level((gpio_num_t)CONFIG_GOV_GPS_POWER_PIN, 0);
        // #endif

        MqttManager::destroy();
        vTaskDelay(pdMS_TO_TICKS(2000));
        WifiManager::stop();
        //esp_deep_sleep_disable_rom_logging();

        set_hibernation_reason(reason);

        vTaskDelay(pdMS_TO_TICKS(2000));

        esp_sleep_enable_timer_wakeup(wake_interval_s * 1000000ULL);
        esp_deep_sleep_start();

    }


#endif


// =============================================================================
//  Hibernação por bateria (ativada via CONFIG_GOV_BATTERY_HIBERNATION)
// =============================================================================
#if CONFIG_GOV_BATTERY_HIBERNATION

    // Publica status CRITICAL_BATTERY e entra em deep_sleep. 
    static void enter_low_battery_deep_sleep(uint32_t battery_mv) {
        SLOG_W("Bateria crítica (%lu mV) - publicando CRITICAL_BATTERY e hibernando por %ds", 
            (unsigned long)battery_mv, (int)CONFIG_GOV_BATTERY_WAKE_INTERVAL_S);
        
            std::string ssid = WifiManager::getSSID();
            char detail[48];
            snprintf(detail, sizeof(detail), "{\"battery_mv\":%lu}", (unsigned long)battery_mv);

            std::string topic = "status/" + g_deviceId;
            publish_proto_status(topic.c_str(),
                                g_macAddress.c_str(),
                                firmware_version.c_str(),
                                ssid.c_str(),
                                (uint32_t)DeviceState::CRITICAL_BATTERY,
                                detail);
            
            vTaskDelay(pdMS_TO_TICKS(3000));

            enter_deep_sleep((uint64_t)CONFIG_GOV_BATTERY_WAKE_INTERVAL_S, HIBERNATION_REASON_LOW_BATTERY);


    }

    static bool check_wake_from_low_battery() {
        if (esp_sleep_get_wakeup_cause() != ESP_SLEEP_WAKEUP_TIMER) return false;
        char reason[32] = {0};
        get_hibernation_reason(reason, sizeof(reason));
        if (strcmp(reason, HIBERNATION_REASON_LOW_BATTERY) != 0) return false;

        if (s_hooks.get_battery_mv == nullptr) {
            set_hibernation_reason(nullptr); // Sem hook nao tem como avaliar
            return false;
        }

        uint32_t mv = s_hooks.get_battery_mv();

        // mv == 0 = leitura indisponível (ADC instavel, hardware com falha,
        // fio solto). Acordar e conectar consome bateria; se ja estamos em
        // hibernacao por bateria baixa E nao conseguimos confirmar recuperacao,
        // o seguro e continuar dormindo. Mantem a flag e agenda novo wake.
        if (mv == 0) {
            SLOG_W("Wake: leitura de bateria indisponivel (0 mV) - dormindo mais %ds por seguranca",
                   (int)CONFIG_GOV_BATTERY_WAKE_INTERVAL_S);
            enter_deep_sleep((uint64_t)CONFIG_GOV_BATTERY_WAKE_INTERVAL_S, HIBERNATION_REASON_LOW_BATTERY);
            // NUNCA retorna
        }

        const uint32_t threshold = CONFIG_GOV_BATTERY_CRITICAL_MV + CONFIG_GOV_BATTERY_HYSTERESIS_MV;

        if (mv < threshold) {
            SLOG_W("Wake: bateria %lu mV ainda < %lu mV (critical + hysteresis) — dormindo mais %ds",
                (unsigned long)mv, (unsigned long)threshold,
                (int)CONFIG_GOV_BATTERY_WAKE_INTERVAL_S);

            // Mantem flag, proximo wake checa denovo
            enter_deep_sleep((uint64_t)CONFIG_GOV_BATTERY_WAKE_INTERVAL_S, HIBERNATION_REASON_LOW_BATTERY);
        }

        SLOG_I("Wake: bateria recuperou (%lu mV >= %lu mV) — retomando boot normal",
            (unsigned long)mv, (unsigned long)threshold);
        set_hibernation_reason(nullptr);
        return false;
    }

#endif


// =============================================================================
//  Tasks operacionais
// =============================================================================

static void telemetry_task(void* /*pvParameters*/) {
    while (true) {
        if (!AppState::is(DeviceState::OPERATIONAL)) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
            if (telemetryPublished == true) {vTaskDelay(pdMS_TO_TICKS(5000)); continue;}
        #endif

        if (s_hooks.read_telemetry == nullptr) {
            #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
                telemetryPublished = true;
            #endif
            vTaskDelay(pdMS_TO_TICKS(TELEMETRY_INTERVAL_MS));
            continue;
        }

        sensor_reading_t readings[GOVERNANCE_MAX_READINGS] = {};
        int n = s_hooks.read_telemetry(readings, GOVERNANCE_MAX_READINGS);
        if (n <= 0) {
            #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
                telemetryPublished = true;
            #endif
            vTaskDelay(pdMS_TO_TICKS(TELEMETRY_INTERVAL_MS));
            continue;
        }
        if (n > GOVERNANCE_MAX_READINGS) n = GOVERNANCE_MAX_READINGS;

        #if CONFIG_GOV_BATTERY_HIBERNATION
            static int s_low_battery_streak = 0;
            if (s_hooks.get_battery_mv != nullptr) {
                uint32_t mv = s_hooks.get_battery_mv();

                // mv == 0 = leitura invalida (hook devolve 0). Nao conta pro streak.
                if (mv > 0 && mv < (uint32_t)CONFIG_GOV_BATTERY_CRITICAL_MV) {
                    int streak = ++s_low_battery_streak;

                    #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
                        // Deep sleep zera a task static a cada wake; persiste no NVS.
                        nvs_handle_t h;
                        if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
                            int32_t persisted = 0;
                            nvs_get_i32(h, "low_bat_streak", &persisted);
                            persisted++;
                            nvs_set_i32(h, "low_bat_streak", persisted);
                            nvs_commit(h);
                            nvs_close(h);
                            streak = persisted;
                        }
                    #endif

                    SLOG_W("Bateria %lu mV < %d mV (streak %d/%d)",
                        (unsigned long)mv, CONFIG_GOV_BATTERY_CRITICAL_MV,
                        streak, CONFIG_GOV_BATTERY_CRITICAL_STREAK);

                    if (streak >= CONFIG_GOV_BATTERY_CRITICAL_STREAK) {
                        enter_low_battery_deep_sleep(mv);
                    }
                } else {
                    s_low_battery_streak = 0;
                    #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
                        nvs_handle_t h;
                        if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
                            nvs_erase_key(h, "low_bat_streak");
                            nvs_commit(h);
                            nvs_close(h);
                        }
                    #endif
                }
            }
        #endif


        MqttMessage msg = {};
        snprintf(msg.topic, sizeof(msg.topic), "telemetry/%s", g_deviceId.c_str());

        DeviceTelemetry proto = DeviceTelemetry_init_zero;
        proto.device_id.funcs.encode = encode_string;
        proto.device_id.arg          = (void*)g_deviceId.c_str();
        proto.timestamp              = (uint64_t)time(NULL);

        for (int i = 0; i < n; ++i) {
            strncpy(proto.readings[i].key, readings[i].key, SENSOR_KEY_MAX_SIZE - 1);
            proto.readings[i].value = readings[i].value;
        }
        proto.readings_count = (pb_size_t)n;

        pb_ostream_t os = pb_ostream_from_buffer(msg.payload, sizeof(msg.payload));
        if (pb_encode(&os, DeviceTelemetry_fields, &proto)) {
            msg.payload_len = os.bytes_written;
            xQueueSend(g_mqttQueue, &msg, 0);
            // NAO seta telemetryPublished, quem seta e o mqtt_publisher_task
            // apos publish real. Enfileirar != publicar.
        } else {
            ESP_LOGE(TAG, "pb_encode DeviceTelemetry falhou: %s", PB_GET_ERROR(&os));
        }

        vTaskDelay(pdMS_TO_TICKS(TELEMETRY_INTERVAL_MS));
    }
}

static void status_task(void* /*pvParameters*/) {
    while (true) {
        if (!AppState::is(DeviceState::OPERATIONAL)) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
            if (statusPublished == true) { vTaskDelay(pdMS_TO_TICKS(5000)); continue; }
        #endif

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
            // NAO seta statusPublished aqui, quem seta e o mqtt_publisher_task
            // apos publish real. Enfileirar != publicar.
        } else {
            ESP_LOGE(TAG, "pb_encode DeviceStatus falhou: %s", PB_GET_ERROR(&os));
        }

        vTaskDelay(pdMS_TO_TICKS(STATUS_INTERVAL_MS));
    }
}

static void mqtt_publisher_task(void* /*pvParameters*/) {
    MqttMessage msg;
    while (true) {
        BaseType_t received = xQueueReceive(g_mqttQueue, &msg, pdMS_TO_TICKS(1000));

        if (received && AppState::is(DeviceState::OPERATIONAL) && MqttManager::isConnected()) {
            MqttManager::publish(msg.payload, msg.payload_len, msg.topic);

            // Flags refletem publish REAL (nao enqueue). Sem isso, o publisher
            // podia dormir com msg de status ainda na fila 
            #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
                if (strncmp(msg.topic, "telemetry/", 10) == 0) telemetryPublished = true;
                else if (strncmp(msg.topic, "status/", 7) == 0)   statusPublished    = true;
            #endif
        }

        #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
            if (telemetryPublished && statusPublished) {
                SLOG_I("Ciclo completo (tele+status publicados) — dormindo por %ds",
                       (int)CONFIG_DEEP_SLEEP_WAKE_INTERVAL_S);
                vTaskDelay(pdMS_TO_TICKS(5000));

                enter_deep_sleep((uint64_t)CONFIG_DEEP_SLEEP_WAKE_INTERVAL_S, HIBERNATION_REASON_DEEP_SLEEP);
                // NUNCA retorna
            }
        #endif
    }
}

// =============================================================================
//  Handlers da state machine
// =============================================================================

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

    AppState::loadPersistedErrors();
    SLOG_I("NVS inicializada com sucesso.");

    char saved_ssid[64]      = {0};
    char saved_token[64]     = {0};
    char saved_device_id[64] = {0};
    char fw_buf[32]          = {0};

    nvs_read_str(NVS_NAMESPACE, "fw_version", fw_buf, sizeof(fw_buf));
    firmware_version = fw_buf;
    nvs_read_str("crypto_store", "wifi_ssid",  saved_ssid,      sizeof(saved_ssid));
    nvs_read_str("crypto_store", "prov_token", saved_token,     sizeof(saved_token));
    nvs_read_str("crypto_store", "device_id",  saved_device_id, sizeof(saved_device_id));

    g_deviceId = std::string(saved_device_id);

    bool hasWifi       = (saved_ssid[0]      != '\0');
    bool hasToken      = (saved_token[0]     != '\0');
    bool isProvisioned = AuthManager::isProvisioned();
    bool hasDeviceId   = (saved_device_id[0] != '\0');

    SLOG_I("Diagnóstico NVS — WiFi:%s | Token:%s | KC Credential:%s | DeviceId:%s | Version:%s",
           hasWifi ? "SIM" : "NÃO", hasToken ? "SIM" : "NÃO",
           isProvisioned ? "SIM" : "NÃO", hasDeviceId ? "SIM" : "NÃO",
           firmware_version.c_str());

    if (!hasDeviceId) {
        SLOG_E("Device_ID nao encontrado na NVS.");
        AppState::setError(ErrorCode::DEVICE_ID_MISSING, "device_id ausente na NVS", {TAG, "handle_nvs_init"});
        return;
    }
    if (!hasWifi) {
        AppState::setError(ErrorCode::WIFI_CREDENTIALS_MISSING, "WiFi credentials ausente na NVS", {TAG, "handle_nvs_init"});
        return;
    }
    if (!isProvisioned) {
        AppState::setError(ErrorCode::CERT_MISSING, "Keycloak credentials ausente na NVS", {TAG, "handle_nvs_init"});
        return;
    }
    if (firmware_version.empty()) {
        AppState::setError(ErrorCode::FIRMWARE_VERSION_MISSING, "Firmware Version not found in NVS", {TAG, "handle_nvs_init"});
        return;
    }

    SLOG_I(">>>>>>>>>>>>> FIRMWARE VERSION [v%s] <<<<<<<<<<<<<<<", firmware_version.c_str());
    AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_nvs_init"});
}

static void handle_wifi_connecting() {
    char saved_ssid[64] = {0};
    char saved_pass[64] = {0};
    nvs_read_str("crypto_store", "wifi_ssid", saved_ssid, sizeof(saved_ssid));
    nvs_read_str("crypto_store", "wifi_pass", saved_pass, sizeof(saved_pass));

    SLOG_I("Tentando conectar à rede: [%s] (máx %d tentativas)", saved_ssid, MAX_WIFI_RETRIES);

    static bool is_wifi_inited = false;
    if (!is_wifi_inited) {
        WifiConfig cfg;
        cfg.ssid        = saved_ssid;
        cfg.password    = saved_pass;
        cfg.max_retries = MAX_WIFI_RETRIES;
        WifiManager::init(cfg);
        is_wifi_inited = true;
    } else {
        WifiManager::reconnect();
    }

    if (!WifiManager::waitForConnection(WatchdogManager::reset)) {
        SLOG_E("Timeout ao conectar em [%s]", saved_ssid);
        AppState::setError(ErrorCode::WIFI_TIMEOUT,
                           "Não foi possível conectar em " + std::string(saved_ssid),
                           {TAG, "handle_wifi_connecting"},
                           {
                               {"failed_ssid", saved_ssid},
                               {"reason", WifiManager::failReasonToString(WifiManager::getFailReason())},
                           });
        return;
    }

    g_macAddress = WifiManager::getMacAddress();
    SLOG_I("WiFi conectado. IP:%s | MAC:%s | SSID:%s | RSSI:%d dBm",
           WifiManager::getIp().c_str(), g_macAddress.c_str(),
           WifiManager::getSSID().c_str(), WifiManager::getRssi());

    AppState::resolveError(ErrorCode::WIFI_TIMEOUT);

    if (!valid_firmware) {
        OtaManager::set_valid_version();
        valid_firmware = true;
    }

    AppState::transition(DeviceState::TIME_SYNC, {TAG, "handle_wifi_connecting"});
}

// Time sync agnóstico de hardware. Usa hooks opcionais do user code:
//   get_persisted_time → primário (RTC persistente tipo DS3231)
//   get_external_time  → fonte externa (GPS/NTP) — refresca RTC periodicamente
//   persist_time       → grava hora no RTC após external sync
static void handle_time_sync() {
    SLOG_I("Sincronizando horário via hooks do user code...");

    bool synced = false;

    // ---- 1. Fonte primária: RTC persistente ----
    if (s_hooks.get_persisted_time != nullptr) {
        time_t epoch = s_hooks.get_persisted_time();
        if (epoch > 0) {
            struct timeval tv = { .tv_sec = epoch, .tv_usec = 0 };
            settimeofday(&tv, nullptr);
            struct tm ti;
            gmtime_r(&epoch, &ti);
            SLOG_I("Horário restaurado do RTC persistente: %04d-%02d-%02d %02d:%02d:%02d UTC",
                   ti.tm_year + 1900, ti.tm_mon + 1, ti.tm_mday,
                   ti.tm_hour, ti.tm_min, ti.tm_sec);
            synced = true;
        } else {
            SLOG_W("RTC persistente sem hora válida.");
        }
    }

    // ---- 2. Boot counter (decide refresh periódico) ----
    int32_t boot_count = 0;
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        nvs_get_i32(h, "boot_count", &boot_count);
        boot_count++;
        nvs_set_i32(h, "boot_count", boot_count);
        nvs_commit(h);
        nvs_close(h);
    }
    bool refresh_due = (CONFIG_GOV_GPS_SYNC_EVERY_N_BOOTS > 0) &&
                       (boot_count % CONFIG_GOV_GPS_SYNC_EVERY_N_BOOTS == 0);

    // ---- 3. Fonte externa: se não tem RTC OU refresh periódico ----
    if (s_hooks.get_external_time != nullptr && (!synced || refresh_due)) {
        if (refresh_due && synced) {
            SLOG_I("Boot #%ld — ciclo de refresh da fonte externa.", boot_count);
        }
        uint32_t timeout_ms = CONFIG_GOV_EXTERNAL_TIME_TIMEOUT_MS;
        SLOG_I("Aguardando hora de fonte externa (timeout %lums)...", (unsigned long)timeout_ms);
        time_t epoch = s_hooks.get_external_time(timeout_ms);
        if (epoch > 0) {
            struct timeval tv = { .tv_sec = epoch, .tv_usec = 0 };
            settimeofday(&tv, nullptr);
            struct tm ti;
            gmtime_r(&epoch, &ti);
            SLOG_I("Horário obtido da fonte externa: %04d-%02d-%02d %02d:%02d:%02d UTC",
                   ti.tm_year + 1900, ti.tm_mon + 1, ti.tm_mday,
                   ti.tm_hour, ti.tm_min, ti.tm_sec);
            synced = true;

            // Persiste no RTC pra sobreviver reboots
            if (s_hooks.persist_time != nullptr) {
                s_hooks.persist_time(epoch);
                SLOG_I("Hora gravada no RTC persistente.");
            }
        } else {
            SLOG_W("Fonte externa sem hora (timeout ou falha).");
        }
    }

    if (!synced) {
        SLOG_W("Nenhuma fonte de tempo disponível. Continuando sem sincronização.");
    }

    AppState::transition(DeviceState::MQTT_INIT, {TAG, "handle_time_sync"});
}

static void handle_sensor_discovery() {
    SLOG_I("Iniciando descoberta de sensores (via hook do user code)...");

    if (s_hooks.sensor_discovery != nullptr) {
        const char* csv = s_hooks.sensor_discovery();
        g_activeSensors = (csv != nullptr) ? csv : "";
    } else {
        g_activeSensors.clear();
    }

    SLOG_I("Sensores ativos: [%s]", g_activeSensors.empty() ? "(nenhum)" : g_activeSensors.c_str());
    AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_sensor_discovery"});
}

static void handle_mqtt_connecting() {
    MqttManager::init_mqtt();

    MqttManager::setCallback([](const std::string& topic, const std::string& payload) {
        ESP_LOGI(TAG, "MQTT recv topic=%s payload=%s", topic.c_str(), payload.c_str());
        bool success = CommandProcessor::manage(payload);
        if (!success) SLOG_E("Command Processor falhou.");
    });

    AppState::transition(DeviceState::MQTT_WAITING_CONNECT, {TAG, "handle_mqtt_connecting"});
}

static void handle_boot_audit() {
    std::string topicCmd = "commands/" + g_deviceId + "/#";
    MqttManager::subscribe(topicCmd, 1);

    char topic[128];
    snprintf(topic, sizeof(topic), "status/%s", g_deviceId.c_str());
    std::string ssid = WifiManager::getSSID();

    nvs_handle_t otaHandler;
    int8_t otaNotified = 1;
    if (nvs_open("ota_store", NVS_READWRITE, &otaHandler) == ESP_OK) {
        nvs_get_i8(otaHandler, "ota_notified", &otaNotified);

        if (!otaNotified) {
            AppState::transition(DeviceState::OTA_SUCCESSFUL, {TAG, "handle_boot_audit"});
            SLOG_I("OTA_SUCCESSFUL detectado, publicando status.");
            publish_proto_status(topic, g_macAddress.c_str(), firmware_version.c_str(),
                                 ssid.c_str(), (uint32_t)AppState::get());
            nvs_set_i8(otaHandler, "ota_notified", 1);
            nvs_commit(otaHandler);
            nvs_close(otaHandler);
            // Vai pra SENSORS_INIT (não OPERATIONAL direto) — precisa descobrir sensores
            AppState::transition(DeviceState::SENSORS_INIT, {TAG, "handle_boot_audit"});
            return;
        }
    }

    nvs_handle_t mainHandler;
    if (nvs_open("main_store", NVS_READWRITE, &mainHandler) == ESP_OK) {
        g_reset_reason = esp_reset_reason();
        if (g_reset_reason == ESP_RST_WDT ||
            g_reset_reason == ESP_RST_TASK_WDT ||
            g_reset_reason == ESP_RST_PANIC) {
            int8_t saved = 0;
            nvs_get_i8(mainHandler, "crash_count", &saved);
            crashCount = saved + 1;
            nvs_set_i8(mainHandler, "crash_count", crashCount);
            nvs_commit(mainHandler);
            SLOG_W("Crash count: [%d]", crashCount);
        } else {
            nvs_set_i8(mainHandler, "crash_count", 0);
            nvs_commit(mainHandler);
        }

        if (crashCount >= MAX_CRASH_COUNT) {
            SLOG_E("LIMITE DE CRASH ATINGIDO — invalidando firmware");
            nvs_set_i8(mainHandler, "crash_count", 0);
            nvs_commit(mainHandler);
            std::string reason = "crashCount";
            OtaManager::set_invalid_version(reason);
        }
        nvs_close(mainHandler);
    }

    std::string lastTarget;
    std::string invalidVer;
    std::string rollback_msg;
    char reason[24] = {0};

    char lastTargetBuf[32] = {0};
    nvs_read_str("ota_store", "target_ver", lastTargetBuf, sizeof(lastTargetBuf));
    lastTarget = lastTargetBuf;

    if (!lastTarget.empty() && OtaManager::verify_rollback(rollback_msg, invalidVer)) {
        SLOG_W("Rollback detectado. Versao invalida: %s", invalidVer.c_str());

        char fw_buf[32] = {0};
        nvs_read_str("main_store", "fw_version", fw_buf, sizeof(fw_buf));
        firmware_version = fw_buf;
        nvs_read_str("ota_store", "rollbackReason", reason, sizeof(reason));

        char detail[192];
        snprintf(detail, sizeof(detail), "{\"invalid_ver\":\"%s\",\"reason\":\"%s\"}", invalidVer.c_str(), reason);

        AppState::transition(DeviceState::FIRMWARE_ROLLBACK, {TAG, "verify_rollback"});
        publish_proto_status(topic, g_macAddress.c_str(), firmware_version.c_str(),
                             ssid.c_str(), (uint32_t)AppState::get(), detail);
    } else if (!lastTarget.empty()) {
        SLOG_W("OTA v%s abortado (download incompleto)", lastTarget.c_str());

        char fw_buf2[32] = {0};
        nvs_read_str("main_store", "fw_version", fw_buf2, sizeof(fw_buf2));
        firmware_version = fw_buf2;

        AppState::setError(ErrorCode::OTA_FAIL, "Error while updating to new firmware.",
            {TAG, "verify_and_update"},
            {{"attempted_version", lastTarget}, {"current_version", firmware_version}});

        nvs_handle_t otaWriter;
        if (nvs_open("ota_store", NVS_READWRITE, &otaWriter) == ESP_OK) {
            nvs_erase_key(otaWriter, "target_ver");
            nvs_commit(otaWriter);
            nvs_close(otaWriter);
        }
    }

    nvs_handle_t commandHandler;
    if (nvs_open("command_store", NVS_READWRITE, &commandHandler) == ESP_OK) {
        int8_t commandNotified = 1;
        nvs_get_i8(commandHandler, "commandNotified", &commandNotified);
        if (!commandNotified) {
            char commandName[32] = {0};
            size_t len = sizeof(commandName);
            nvs_get_str(commandHandler, "lastCommand", commandName, &len);

            char detail[64];
            snprintf(detail, sizeof(detail), "{\"command\":\"%s\"}", commandName);
            SLOG_I("Notificando comando concluído [%s].", commandName);
            AppState::transition(DeviceState::COMMAND_COMPLETE, {TAG, "handle_boot_audit"});

            publish_proto_status(topic, g_macAddress.c_str(), firmware_version.c_str(),
                                 ssid.c_str(), (uint32_t)AppState::get(), detail);

            nvs_set_i8(commandHandler, "commandNotified", 1);
            nvs_commit(commandHandler);
        }
        nvs_close(commandHandler);
    }

    AppState::resolveError(ErrorCode::MQTT_INIT_FAIL);
    AppState::resolveError(ErrorCode::MQTT_DISCONNECTED);

    // Sempre passa por SENSORS_INIT antes de qualquer outra coisa. Se resolveError
    // enfileirou eventos, o main loop detecta hasQueuedErrors apos OPERATIONAL e
    // dispara handle_error na iteracao seguinte (sem pular sensor discovery).
    AppState::transition(DeviceState::SENSORS_INIT, {TAG, "handle_boot_audit"});
}

// Boot watchdog: aguarda CONFIG_GOV_BOOT_TIMEOUT_S; 
// atingido, forca esp_restart() ou esp_deep_sleep(). 
static void boot_timeout_task(void* /*pv*/) {
    vTaskDelay(pdMS_TO_TICKS(CONFIG_GOV_BOOT_TIMEOUT_S * 1000));

    // TODO: pensar num motivo para nao reiniciar o esp se estiver na tomada, por enquanto reiniciamos mesmo assim
    // #if !defined(CONFIG_GOV_FIRMWARE_DEEP_SLEEP)
    //     if (s_operational_reached) {  };
    // #endif
   // Correção: Adicionado o %d para casar com a variável inteira do timeout
    ESP_LOGE(TAG, "Boot timeout: device ligado a mais de %d segundos (estado atual: %s)",
        (int)CONFIG_GOV_BOOT_TIMEOUT_S, AppState::toString(AppState::get()));
    
    #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
    enter_deep_sleep((uint64_t) CONFIG_DEEP_SLEEP_WAKE_INTERVAL_S, HIBERNATION_REASON_TIMEOUT);
    #endif

    // se nao tem deep_sleep ativado, so reinicia 
    esp_restart();
    
}

static void handle_operational() {
    static bool started = false;
    if (!started) {
        g_mqttQueue = xQueueCreate(10, sizeof(MqttMessage));
        xTaskCreate(telemetry_task,      "telemetry", 4096, NULL, 4, NULL);
        xTaskCreate(status_task,         "status",    4096, NULL, 4, NULL);
        xTaskCreate(mqtt_publisher_task, "mqtt_pub",  4096, NULL, 5, NULL);
        started = true;
        SLOG_I("Tasks operacionais iniciadas (telemetria %ds / status %ds) | Sensores: %s",
               TELEMETRY_INTERVAL_MS / 1000, STATUS_INTERVAL_MS / 1000,
               g_activeSensors.empty() ? "(nenhum)" : g_activeSensors.c_str());
    }
    vTaskDelay(pdMS_TO_TICKS(500));
}

static void handle_error() {
    SLOG_E("Handler de erro. Eventos na fila: %s", AppState::hasQueuedErrors() ? "SIM" : "NÃO");

    if (!AppState::hasQueuedErrors()) {
        #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
            telemetryPublished = false;
            statusPublished    = false;
        #endif
        AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_error"});
        return;
    }

    if (WifiManager::getIp().empty()) {
        SLOG_W("Sem conectividade. Aguardando 10s antes de reconectar WiFi...");
        vTaskDelay(pdMS_TO_TICKS(10000));
        AppState::transition(DeviceState::WIFI_CONNECTING, {TAG, "handle_error"});
        return;
    }

    if (!MqttManager::isConnected()) {
        SLOG_W("MQTT desconectado. Aguardando reconexão...");
        MqttManager::tryReconnect();
        vTaskDelay(pdMS_TO_TICKS(15000));
        return;
    }

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
            SLOG_E("Publicando erro [%s]: %s", AppState::toString(e.code), e.msg.c_str());
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

    if (AppState::hasActiveErrors()) {
        SLOG_W("Erros ativos sem resolução. Retomando operação.");
    } else {
        SLOG_I("Todos os erros resolvidos.");
    }

    // Reseta flags de ciclo antes de voltar pra OPERATIONAL 
    #if CONFIG_GOV_FIRMWARE_DEEP_SLEEP
        telemetryPublished = false;
        statusPublished    = false;
    #endif

    AppState::transition(DeviceState::OPERATIONAL, {TAG, "handle_error"});
}

static void handle_waiting_instruction() {
    SLOG_W("Waiting instruction...");

    vTaskDelay(pdMS_TO_TICKS(5000));
    // while (1) {
    //     WatchdogManager::reset();
    //     vTaskDelay(pdMS_TO_TICKS(5000));
    // }
}

// =============================================================================
//  API 
// =============================================================================

esp_err_t governance_core_init(const governance_hooks_t* hooks) {
    if (hooks == nullptr) {
        ESP_LOGE(TAG, "governance_core_init: hooks é NULL");
        return ESP_ERR_INVALID_ARG;
    }

    s_hooks = *hooks;

    // Boot watchdog — cria ANTES de qualquer inicialização pesada.
    // Se timeout, reseta.
    xTaskCreate(boot_timeout_task, "boot_wd", 4096, NULL, 1, NULL);
    

    #if CONFIG_GOV_BATTERY_HIBERNATION

        esp_err_t nvs_err = nvs_flash_init();
        if (nvs_err == ESP_ERR_NVS_NO_FREE_PAGES || nvs_err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
            nvs_flash_erase();
            nvs_flash_init();
        }
        check_wake_from_low_battery();

    #endif

    AppState::init();

    WatchdogManager::init(60000, true);
    WatchdogManager::addToCurrentTask();

    while (true) {
        WatchdogManager::reset();

        // Se estamos em OPERATIONAL mas erros foram enfileirados (ex: resolves
        // do handle_boot_audit, ou erros gerados por tasks async), redireciona
        // pra ERROR pra drenar a fila. Sem isso, resolves ficam presos ate um
        // proximo setError disparar transicao pra ERROR.
        if (AppState::get() == DeviceState::OPERATIONAL && AppState::hasQueuedErrors()) {
            AppState::transition(DeviceState::ERROR, {TAG, "main_loop"});
        }

        switch (AppState::get()) {
            case DeviceState::NVS_INIT:              handle_nvs_init();               break;
            case DeviceState::WIFI_CONNECTING:       handle_wifi_connecting();        break;
            case DeviceState::TIME_SYNC:             handle_time_sync();              break;
            case DeviceState::MQTT_INIT:             handle_mqtt_connecting();        break;
            case DeviceState::MQTT_WAITING_CONNECT:  vTaskDelay(pdMS_TO_TICKS(5000)); break;
            case DeviceState::ERROR:                 handle_error();                  break;
            case DeviceState::BOOT_AUDIT:            handle_boot_audit();             break;
            case DeviceState::SENSORS_INIT:          handle_sensor_discovery();       break;
            case DeviceState::WAITING_RESPONSE:      handle_waiting_instruction();    break;
            case DeviceState::OPERATIONAL:           handle_operational();            break;
            case DeviceState::OTA_FOUND:                                              break;
            case DeviceState::OTA_DOWNLOADING:                                        break;
            case DeviceState::FIRMWARE_ROLLBACK:                                      break;
            case DeviceState::OTA_SUCCESSFUL:                                         break;
            case DeviceState::HTTP_INIT:                                              break;
            case DeviceState::HTTP_REQUEST:                                           break;
            case DeviceState::REBOOTING:                                              break;
            case DeviceState::PROVISIONING_SUCCESS:                                   break;
            case DeviceState::PROVISIONING:                                           break;
            case DeviceState::WIFI_AP_MODE:                                           break;
            case DeviceState::COMMAND_COMPLETE:                                       break;
            case DeviceState::CRITICAL_BATTERY:                                       break;
        }
    }

    return ESP_OK;  // inatingível
}
