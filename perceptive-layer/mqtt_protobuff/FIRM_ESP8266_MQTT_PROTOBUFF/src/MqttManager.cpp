#include "MqttManager.h"
#include "AuthManager.h"
#include "../config.h"
#include <ESP8266WiFi.h>
#include <PubSubClient.h>

#define RECONNECT_DELAY_MS 5000
// CONNECT carrega o JWT inteiro no username (~1-2KB)
#define MQTT_BUFFER_SIZE   4096

static const char* TAG = "[MqttManager]";

static WiFiClient   s_net;
static PubSubClient s_client(s_net);
static String       s_client_id;
static String       s_current_jwt;   // PubSubClient guarda so o ponteiro durante o connect

static uint32_t s_last_attempt_ms = 0;
static bool     s_ever_attempted  = false;
static uint32_t s_refresh_at_ms   = 0;   // 0 = sem refresh agendado

static MqttManager::MessageCallback s_message_callback = nullptr;
static String s_sub_topic;   // so commands/{id}/# por enquanto
static int    s_sub_qos = 1;

static void resubscribe_all() {
    if (s_sub_topic.length() == 0) return;
    bool ok = s_client.subscribe(s_sub_topic.c_str(), s_sub_qos);
    Serial.printf("%s %s no topico: %s\n", TAG, ok ? "Inscrito" : "FALHA ao inscrever", s_sub_topic.c_str());
}

static void on_message(char* topic, uint8_t* payload, unsigned int length) {
    Serial.printf("%s Comando recebido - topico: %s (%u bytes)\n", TAG, topic, length);
    if (!s_message_callback) return;
    String body;
    body.reserve(length);
    for (unsigned int i = 0; i < length; i++) body += (char)payload[i];
    s_message_callback(String(topic), body);
}

static void schedule_refresh() {
    // margem = min(MARGIN, 20% do TTL), piso 30s; refresh >= 60s
    int remaining  = AuthManager::getJwtRemainingSeconds();
    int margin_eff = min(GOV_KC_TOKEN_MARGIN_S, remaining / 5);
    if (margin_eff < 30) margin_eff = 30;
    int refresh_at = remaining - margin_eff;
    if (refresh_at < 60) refresh_at = 60;
    s_refresh_at_ms = millis() + (uint32_t)refresh_at * 1000UL;
    if (s_refresh_at_ms == 0) s_refresh_at_ms = 1;
    Serial.printf("%s Refresh preventivo de JWT em %ds (TTL=%ds)\n", TAG, refresh_at, remaining);
}

static void try_connect() {
    s_last_attempt_ms = millis();
    s_ever_attempted  = true;

    s_current_jwt = AuthManager::getJwt();
    if (s_current_jwt.length() == 0) {
        Serial.printf("%s JWT vazio - nova tentativa em %ds\n", TAG, RECONNECT_DELAY_MS / 1000);
        return;
    }

    Serial.printf("%s Conectando em %s:%d (client_id=%s)...\n",
                  TAG, GOV_MQTT_HOST, GOV_MQTT_PORT, s_client_id.c_str());

    // sessao persistente
    bool ok = s_client.connect(s_client_id.c_str(), s_current_jwt.c_str(), "unused",
                               nullptr, 0, false, nullptr, false);
    if (ok) {
        Serial.printf("%s Conectado ao Broker MQTT.\n", TAG);
        resubscribe_all();
        schedule_refresh();
        return;
    }

    int rc = s_client.state();
    Serial.printf("%s Falha no connect (state=%d)\n", TAG, rc);
    // 4/5: JWT invalido ou expirado
    if (rc == MQTT_CONNECT_BAD_CREDENTIALS || rc == MQTT_CONNECT_UNAUTHORIZED) {
        AuthManager::invalidateCache();
    }
}

void MqttManager::init_mqtt(const String& clientId) {
    s_client_id = clientId;
    s_client.setServer(GOV_MQTT_HOST, GOV_MQTT_PORT);
    s_client.setCallback(on_message);
    if (!s_client.setBufferSize(MQTT_BUFFER_SIZE)) {
        Serial.printf("%s setBufferSize(%d) falhou (heap)\n", TAG, MQTT_BUFFER_SIZE);
    }
    s_client.setKeepAlive(30);
    s_client.setSocketTimeout(10);
    try_connect();
}

void MqttManager::loop() {
    if (s_client.connected()) {
        if (s_refresh_at_ms != 0 && (int32_t)(millis() - s_refresh_at_ms) >= 0) {
            Serial.printf("%s Safe JWT refresh - invalidating cache + reconnecting.\n", TAG);
            s_refresh_at_ms = 0;
            AuthManager::invalidateCache();
            s_client.disconnect();
            try_connect();
            return;
        }
        s_client.loop();
        return;
    }

    if (!WiFi.isConnected()) return;
    if (!s_ever_attempted || millis() - s_last_attempt_ms >= RECONNECT_DELAY_MS) {
        try_connect();
    }
}

bool MqttManager::isConnected() {
    return s_client.connected();
}

bool MqttManager::publish(const uint8_t* data, size_t len, const char* topic) {
    if (!s_client.connected()) {
        Serial.printf("%s publish() sem conexao [%s]\n", TAG, topic);
        return false;
    }
    bool ok = s_client.publish(topic, data, len, false);
    Serial.printf("%s Publicado %s (%u bytes) no topico [%s]\n",
                  TAG, ok ? "OK" : "FALHOU", (unsigned)len, topic);
    return ok;
}

void MqttManager::subscribe(const String& topic, int qos) {
    s_sub_topic = topic;
    s_sub_qos   = qos;
    if (s_client.connected()) resubscribe_all();
}

void MqttManager::setCallback(MessageCallback cb) {
    s_message_callback = cb;
}
