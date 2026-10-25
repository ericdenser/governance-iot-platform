#pragma once

// Configuracao do firmware ESP8266. WiFi, device_id, token e versao vem do /prov.txt.
// Layout de flash fixo: 4MB (FS:2MB OTA:~1019KB), eesz=4M2M. Mudar move o LittleFS.

#define WIFI_CONNECT_TIMEOUT_MS   20000

// ---- Plataforma ----
// URLs sem barra final. https sem validar CA (setInsecure).
#define GOV_MDM_BASE_URL          "http://REPLACE_ME:8082"
#define GOV_KEYCLOAK_URL          "http://REPLACE_ME:8080"
#define GOV_KEYCLOAK_REALM        "IOT-REALM"
#define GOV_KC_TOKEN_MARGIN_S     300

// Broker so TCP (1883): PubSubClient nao suporta websocket
#define GOV_MQTT_HOST             "REPLACE_ME"
#define GOV_MQTT_PORT             1883

#define GOV_NTP_SERVER            "pool.ntp.org"

// ---- Intervalos ----
#define GOV_STATUS_INTERVAL_MS    30000
#define GOV_TELEMETRY_INTERVAL_MS 5000
#define GOV_BOOT_TIMEOUT_S        180   // reinicia se nao chegar em OPERATIONAL

// CSV publicado em active_sensors do DeviceStatus. Vazio = nenhum.
#define GOV_ACTIVE_SENSORS        ""
