// Governance IoT - firmware ESP8266 (Arduino)
// Comandos: so UPDATE (sem rollback). Erro publicado: so OTA_FAIL.

#include <ESP8266WiFi.h>
#include <time.h>

#include "config.h"
#include "src/AppState.h"
#include "src/AuthManager.h"
#include "src/MqttManager.h"
#include "src/CommandProcessor.h"
#include "src/OtaManager.h"

#include "src/pb/pb_encode.h"
#include "src/pb/device_status.pb.h"
#include "src/pb/device_telemetry.pb.h"
#include "src/pb/device_error.pb.h"

#define PROVISIONING_RETRY_MS 30000
#define TIME_SYNC_TIMEOUT_MS  20000
#define MIN_VALID_EPOCH       1700000000

static String   g_deviceId;
static String   g_macAddress;
static uint32_t g_lastStatusMs    = 0;
static uint32_t g_lastTelemetryMs = 0;
static uint32_t g_lastProvisionMs = 0;
static bool     g_provisionTried  = false;
static bool     g_reachedOperational = false;
static OtaCommand g_otaCmd;

// =============================================================================
//  nanopb helpers
// =============================================================================
static bool encode_string(pb_ostream_t* stream, const pb_field_t* field, void* const* arg) {
    const char* str = (const char*)(*arg);
    if (!str) str = "";
    return pb_encode_tag_for_field(stream, field) &&
           pb_encode_string(stream, (const uint8_t*)str, strlen(str));
}

static bool publish_proto_status(DeviceState state) {
    uint8_t buf[256] = {};
    String  ssid     = WiFi.SSID();
    String  topic    = "status/" + g_deviceId;
    String  fwVer    = OtaManager::currentVersion();

    DeviceStatus msg = DeviceStatus_init_zero;
    msg.mac.funcs.encode        = encode_string; msg.mac.arg        = (void*)g_macAddress.c_str();
    msg.fw_version.funcs.encode = encode_string; msg.fw_version.arg = (void*)fwVer.c_str();
    msg.ssid.funcs.encode       = encode_string; msg.ssid.arg       = (void*)ssid.c_str();
    msg.state                   = (uint32_t)state;
    msg.timestamp               = (uint64_t)time(nullptr);
    if (strlen(GOV_ACTIVE_SENSORS) > 0) {
        msg.active_sensors.funcs.encode = encode_string;
        msg.active_sensors.arg          = (void*)GOV_ACTIVE_SENSORS;
    }

    pb_ostream_t os = pb_ostream_from_buffer(buf, sizeof(buf));
    if (!pb_encode(&os, DeviceStatus_fields, &msg)) {
        Serial.printf("[GOV] pb_encode DeviceStatus falhou: %s\n", PB_GET_ERROR(&os));
        return false;
    }
    return MqttManager::publish(buf, os.bytes_written, topic.c_str());
}

static bool publish_proto_error(ErrorCode code, const String& errorMsg, const char* source,
                                const String& extra) {
    uint8_t buf[512] = {};
    String  ssid     = WiFi.SSID();
    String  topic    = "error/" + g_deviceId;
    String  fwVer    = OtaManager::currentVersion();

    DeviceError msg = DeviceError_init_zero;
    msg.device_id.funcs.encode    = encode_string; msg.device_id.arg    = (void*)g_deviceId.c_str();
    msg.mac.funcs.encode          = encode_string; msg.mac.arg          = (void*)g_macAddress.c_str();
    msg.fw_version.funcs.encode   = encode_string; msg.fw_version.arg   = (void*)fwVer.c_str();
    msg.ssid.funcs.encode         = encode_string; msg.ssid.arg         = (void*)ssid.c_str();
    msg.error_code                = (uint32_t)code;
    msg.error_msg.funcs.encode    = encode_string; msg.error_msg.arg    = (void*)errorMsg.c_str();
    msg.error_source.funcs.encode = encode_string; msg.error_source.arg = (void*)source;
    msg.resolved                  = false;
    msg.timestamp                 = (uint64_t)time(nullptr);
    if (extra.length() > 0) {
        msg.extra.funcs.encode = encode_string; msg.extra.arg = (void*)extra.c_str();
    }

    pb_ostream_t os = pb_ostream_from_buffer(buf, sizeof(buf));
    if (!pb_encode(&os, DeviceError_fields, &msg)) {
        Serial.printf("[GOV] pb_encode DeviceError falhou: %s\n", PB_GET_ERROR(&os));
        return false;
    }
    return MqttManager::publish(buf, os.bytes_written, topic.c_str());
}

static bool publish_proto_telemetry() {
    // static: ~600B, nao cabe na stack de 4KB
    static DeviceTelemetry proto;
    static uint8_t buf[512];
    String topic = "telemetry/" + g_deviceId;

    proto = DeviceTelemetry_init_zero;
    proto.device_id.funcs.encode = encode_string;
    proto.device_id.arg          = (void*)g_deviceId.c_str();
    proto.timestamp              = (uint64_t)time(nullptr);

    int n = 0;
    strncpy(proto.readings[n].key, "rssi", SENSOR_KEY_MAX_SIZE - 1);
    proto.readings[n++].value = (float)WiFi.RSSI();
    strncpy(proto.readings[n].key, "free_heap", SENSOR_KEY_MAX_SIZE - 1);
    proto.readings[n++].value = (float)ESP.getFreeHeap();
    proto.readings_count = (pb_size_t)n;

    pb_ostream_t os = pb_ostream_from_buffer(buf, sizeof(buf));
    if (!pb_encode(&os, DeviceTelemetry_fields, &proto)) {
        Serial.printf("[GOV] pb_encode DeviceTelemetry falhou: %s\n", PB_GET_ERROR(&os));
        return false;
    }
    return MqttManager::publish(buf, os.bytes_written, topic.c_str());
}

// =============================================================================
//  Handlers de estado
// =============================================================================
static void handle_nvs_init() {
    if (!AuthManager::begin()) {
        Serial.println("[GOV] LittleFS indisponivel - reiniciando em 5s");
        delay(5000);
        ESP.restart();
    }
    OtaManager::bootAudit();
    g_deviceId = AuthManager::getDeviceId();
    bool provisioned = AuthManager::isProvisioned();
    bool hasProvFile = AuthManager::hasProvisioningFile();
    Serial.printf("[GOV] >>>>>>>>>> FIRMWARE VERSION [%s] | DeviceId: %s | Provisionado: %s | /prov.txt: %s <<<<<<<<<<\n",
                  OtaManager::currentVersion().c_str(), g_deviceId.c_str(),
                  provisioned ? "SIM" : "NAO", hasProvFile ? "SIM" : "NAO");

    if (!provisioned && !hasProvFile) {
        AppState::transition(DeviceState::ERROR, "handle_nvs_init");
        return;
    }
    if (AuthManager::getWifiSsid().length() == 0) {
        Serial.println("[GOV] SSID vazio (/prov.txt sem wifi_ssid?)");
        AppState::transition(DeviceState::ERROR, "handle_nvs_init");
        return;
    }
    AppState::transition(DeviceState::WIFI_CONNECTING, "handle_nvs_init");
}

static void handle_wifi_connecting() {
    String ssid = AuthManager::getWifiSsid();
    Serial.printf("[GOV] Conectando em [%s]...\n", ssid.c_str());
    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(ssid, AuthManager::getWifiPassword());

    uint32_t start = millis();
    while (!WiFi.isConnected() && millis() - start < WIFI_CONNECT_TIMEOUT_MS) {
        delay(250);
    }
    if (!WiFi.isConnected()) {
        Serial.printf("[GOV] Timeout WiFi (status=%d) - nova tentativa\n", WiFi.status());
        WiFi.disconnect();
        delay(2000);
        return;
    }

    g_macAddress = WiFi.macAddress();
    Serial.printf("[GOV] WiFi conectado. IP:%s | MAC:%s | RSSI:%d dBm\n",
                  WiFi.localIP().toString().c_str(), g_macAddress.c_str(), WiFi.RSSI());

    // reconexao apos queda: hora ja sincronizada e MQTT reconecta sozinho
    if (g_reachedOperational) {
        AppState::transition(DeviceState::OPERATIONAL, "handle_wifi_connecting");
        return;
    }
    AppState::transition(DeviceState::TIME_SYNC, "handle_wifi_connecting");
}

static void handle_time_sync() {
    configTime(0, 0, GOV_NTP_SERVER);
    uint32_t start = millis();
    while (time(nullptr) < MIN_VALID_EPOCH && millis() - start < TIME_SYNC_TIMEOUT_MS) {
        delay(200);
    }
    if (time(nullptr) < MIN_VALID_EPOCH) {
        // SNTP segue em background; status sai com timestamp errado ate sincronizar
        Serial.println("[GOV] NTP sem resposta - seguindo sem hora valida");
    } else {
        Serial.printf("[GOV] Hora sincronizada: epoch=%lld\n", (long long)time(nullptr));
    }

    if (AuthManager::isProvisioned()) {
        AppState::transition(DeviceState::MQTT_INIT, "handle_time_sync");
    } else {
        AppState::transition(DeviceState::PROVISIONING, "handle_time_sync");
    }
}

static void handle_provisioning() {
    if (g_provisionTried && millis() - g_lastProvisionMs < PROVISIONING_RETRY_MS) return;
    g_provisionTried  = true;
    g_lastProvisionMs = millis();

    String err;
    if (!AuthManager::provision(g_macAddress, err)) {
        Serial.printf("[GOV] Provisioning falhou: %s (retry em %ds)\n",
                      err.c_str(), PROVISIONING_RETRY_MS / 1000);
        return;
    }
    AppState::transition(DeviceState::MQTT_INIT, "handle_provisioning");
}

static void handle_mqtt_init() {
    g_deviceId = AuthManager::getDeviceId();
    MqttManager::setCallback([](const String& /*topic*/, const String& payload) {
        if (!CommandProcessor::manage(payload)) Serial.println("[GOV] Command Processor falhou.");
    });
    MqttManager::subscribe("commands/" + g_deviceId + "/#", 1);

    AppState::transition(DeviceState::MQTT_WAITING_CONNECT, "handle_mqtt_init");
    MqttManager::init_mqtt(AuthManager::getClientId());
}

static void handle_mqtt_waiting_connect() {
    MqttManager::loop();
    if (!MqttManager::isConnected()) return;

    // PROVISIONING_SUCCESS gera DEVICE_PROVISIONED no event-handler
    if (AuthManager::isProvisionAnnouncePending()) {
        if (!publish_proto_status(DeviceState::PROVISIONING_SUCCESS)) return;
        AuthManager::clearProvisionAnnounce();
        Serial.println("[GOV] PROVISIONING_SUCCESS publicado.");
    }

    // OTA_SUCCESSFUL gera DEVICE_UPDATED
    if (OtaManager::isSuccessAnnouncePending()) {
        AppState::transition(DeviceState::OTA_SUCCESSFUL, "handle_mqtt_waiting_connect");
        if (!publish_proto_status(DeviceState::OTA_SUCCESSFUL)) return;
        OtaManager::clearSuccessAnnounce();
        Serial.println("[GOV] OTA_SUCCESSFUL publicado.");
    }

    g_reachedOperational = true;
    AppState::transition(DeviceState::OPERATIONAL, "handle_mqtt_waiting_connect");
    g_lastStatusMs    = millis() - GOV_STATUS_INTERVAL_MS;     // publica status ja
    g_lastTelemetryMs = millis() - GOV_TELEMETRY_INTERVAL_MS;
}

static void handle_operational() {
    if (!WiFi.isConnected()) {
        Serial.println("[GOV] WiFi caiu.");
        AppState::transition(DeviceState::WIFI_CONNECTING, "handle_operational");
        return;
    }

    MqttManager::loop();
    if (!MqttManager::isConnected()) return;

    // OTA_FAIL (do download ou do boot audit) ainda nao publicado
    String failMsg, failExtra;
    if (OtaManager::peekPendingFailure(failMsg, failExtra)) {
        Serial.printf("[GOV] Publicando erro [OTA_FAIL]: %s\n", failMsg.c_str());
        if (publish_proto_error(ErrorCode::OTA_FAIL, failMsg, "OtaManager::update", failExtra)) {
            OtaManager::clearPendingFailure();
        }
    }

    if (CommandProcessor::takePendingOta(g_otaCmd)) {
        AppState::transition(DeviceState::OTA_FOUND, "handle_operational");
        return;
    }

    uint32_t now = millis();
    if (now - g_lastStatusMs >= GOV_STATUS_INTERVAL_MS) {
        if (publish_proto_status(DeviceState::OPERATIONAL)) g_lastStatusMs = now;
    }
    if (now - g_lastTelemetryMs >= GOV_TELEMETRY_INTERVAL_MS) {
        if (publish_proto_telemetry()) g_lastTelemetryMs = now;
    }
}

static void handle_ota_found() {
    AppState::transition(DeviceState::OTA_DOWNLOADING, "handle_ota_found");
    String err;
    OtaManager::update(g_otaCmd, err);   // sucesso reinicia aqui dentro

    Serial.printf("[GOV] OTA falhou: %s\n", err.c_str());
    OtaManager::savePendingFailure(err, g_otaCmd.version, g_otaCmd.sha);
    g_otaCmd = OtaCommand();
    AppState::transition(DeviceState::OPERATIONAL, "handle_ota_found");
}

// Sem dados pra operar: boot watchdog reinicia
static void handle_error() {
    Serial.println("[GOV] Device sem credenciais e sem /prov.txt valido. "
                   "Grave a imagem LittleFS do flash package (ver README).");
    delay(10000);
}

// =============================================================================
//  Arduino entry points
// =============================================================================
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println("[GOV] Boot ESP8266 - Governance IoT");
}

void loop() {
    // Boot watchdog: reinicia se nao chegar em OPERATIONAL
    if (!g_reachedOperational && millis() > (uint32_t)GOV_BOOT_TIMEOUT_S * 1000UL) {
        Serial.printf("[GOV] Nao chegou em OPERATIONAL em %ds - reiniciando\n", GOV_BOOT_TIMEOUT_S);
        delay(100);
        ESP.restart();
    }

    switch (AppState::get()) {
        case DeviceState::NVS_INIT:             handle_nvs_init();             break;
        case DeviceState::WIFI_CONNECTING:      handle_wifi_connecting();      break;
        case DeviceState::TIME_SYNC:            handle_time_sync();            break;
        case DeviceState::PROVISIONING:         handle_provisioning();         break;
        case DeviceState::MQTT_INIT:            handle_mqtt_init();            break;
        case DeviceState::MQTT_WAITING_CONNECT: handle_mqtt_waiting_connect(); break;
        case DeviceState::OPERATIONAL:          handle_operational();          break;
        case DeviceState::OTA_FOUND:            handle_ota_found();            break;
        case DeviceState::ERROR:                handle_error();                break;
        default:
            Serial.printf("[GOV] Estado inesperado %s - reiniciando\n", AppState::toString(AppState::get()));
            delay(1000);
            ESP.restart();
    }
    delay(10);
}
