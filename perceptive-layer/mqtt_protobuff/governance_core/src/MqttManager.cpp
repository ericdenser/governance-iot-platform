#include "MqttManager.h"
#include "mqtt_client.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "AuthManager.h"
#include "AppState.h"
#include "esp_crt_bundle.h"
#include "esp_timer.h"
#include <vector>
#include <utility>

#define BROKER_URL     CONFIG_GOV_MQTT_BROKER_URI
#define MAX_RECONNECT_ATTEMPTS  5
#define RECONNECT_DELAY_MS      5000

static const char *TAG = "MqttManager";
esp_mqtt_client_handle_t mqtt_client = NULL;
static MqttManager::MessageCallback s_message_callback = nullptr;

static std::string s_current_jwt;
static std::string s_device_id;

static int                s_reconnect_count = 0;
static esp_timer_handle_t s_reconnect_timer = NULL;
static esp_timer_handle_t s_refresh_timer   = NULL;  // refresh JWT
static bool               s_is_connected    = false;
static bool               s_error_reported  = false;

static std::vector<std::pair<std::string, int>> s_subscriptions;

static void resubscribe_all() {
    for (const auto& [topic, qos] : s_subscriptions) {
        int msg_id = esp_mqtt_client_subscribe(mqtt_client, topic.c_str(), qos);
        if (msg_id >= 0) {
            ESP_LOGI(TAG, "Re-inscrito no tópico: %s (msg_id=%d)", topic.c_str(), msg_id);
        } else {
            ESP_LOGE(TAG, "Falha ao re-inscrever no tópico: %s", topic.c_str());
        }
    }
}


static void refresh_timer_cb(void*) {
    ESP_LOGI(TAG, "Safe JWT refresh — invalidating cache + reconnecting.");
    AuthManager::invalidateCache();
    if (mqtt_client) {
        esp_mqtt_client_disconnect(mqtt_client);
        // MQTT_EVENT_DISCONNECTED triggers schedule_reconnect -> reconnect_timer_cb
        // -> getJwt() new fetch (cache invalidated) -> CONNECT with new JWT.
    }
}

// Chamado pelo timer após RECONNECT_DELAY_MS para tentar reconectar
static void reconnect_timer_cb(void*) {
    s_reconnect_count++;
    ESP_LOGW(TAG, "[%d/%d] Tentando reconectar ao broker MQTT...",
             s_reconnect_count, MAX_RECONNECT_ATTEMPTS);
    
    s_current_jwt = AuthManager::getJwt();
    if (s_current_jwt.empty()) {
        ESP_LOGE(TAG, "JWT vazio no reconnect - abortando tentativa.");
        return;
    }

    esp_mqtt_client_config_t cfg = {};
    cfg.broker.address.uri = CONFIG_GOV_MQTT_BROKER_URI;
    cfg.broker.verification.crt_bundle_attach = esp_crt_bundle_attach;
    cfg.credentials.username = s_current_jwt.c_str();
    cfg.credentials.authentication.password = "unused";
    cfg.credentials.client_id = s_device_id.c_str();
    cfg.session.disable_clean_session = true;
    cfg.buffer.size     = 4096;
    cfg.buffer.out_size = 4096;
    esp_mqtt_set_config(mqtt_client, &cfg);

    esp_mqtt_client_reconnect(mqtt_client);
             
}

// Agenda próxima tentativa ou seta erro se esgotou as tentativas
static void schedule_reconnect() {
    if (s_reconnect_count >= MAX_RECONNECT_ATTEMPTS) {
        if (!s_error_reported) {
            s_error_reported = true;
            ESP_LOGE(TAG, "Broker inacessível após %d tentativas. Setando erro.", MAX_RECONNECT_ATTEMPTS);
            AppState::setError(
                ErrorCode::MQTT_DISCONNECTED,
                "Broker MQTT inacessível após 5 tentativas de reconexão",
                {TAG, "schedule_reconnect"}
            );
        }
        return;
    }
    // stop ignora se o timer não está rodando
    esp_timer_stop(s_reconnect_timer);
    esp_timer_start_once(s_reconnect_timer, (uint64_t)RECONNECT_DELAY_MS * 1000ULL);
    ESP_LOGW(TAG, "Reconexão agendada em %ds (tentativa %d/%d).",
             RECONNECT_DELAY_MS / 1000, s_reconnect_count + 1, MAX_RECONNECT_ATTEMPTS);
}

static void mqtt_event_handler(void *handler_args, esp_event_base_t base, int32_t event_id, void *event_data) {

    esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;

    switch ((esp_mqtt_event_id_t)event_id) {

        case MQTT_EVENT_CONNECTED:
            ESP_LOGI(TAG, "Conectado ao Broker MQTT com sucesso!");
            s_is_connected    = true;
            s_reconnect_count = 0;
            s_error_reported  = false;
            esp_timer_stop(s_reconnect_timer);
            if (AppState::is(DeviceState::MQTT_WAITING_CONNECT)) {
                AppState::transition(DeviceState::BOOT_AUDIT, {TAG, "mqtt_event_handler"});
            } else {
                resubscribe_all();
                AppState::resolveError(ErrorCode::MQTT_DISCONNECTED);
                AppState::transition(DeviceState::ERROR, {TAG, "mqtt_event_handler"});
            }
            break;

        case MQTT_EVENT_DISCONNECTED:
            ESP_LOGW(TAG, "Desconectado do Broker MQTT.");
            s_is_connected = false;
            schedule_reconnect();
            break;

        case MQTT_EVENT_DATA: {

            std::string topic(event->topic, event->topic_len);
            std::string payload(event->data, event->data_len);

            ESP_LOGI(TAG, "Comando recebido — tópico: %.*s (%d bytes)",
                     (int)event->topic_len, event->topic, (int)event->data_len);

            if (s_message_callback) {
                s_message_callback(topic, payload);
            }
            
            break;
        }

        case MQTT_EVENT_ERROR: {
            int etype = event->error_handle ? event->error_handle->error_type : -1;
            int rcode = event->error_handle ? event->error_handle->connect_return_code : -1;
            ESP_LOGE(TAG, "Erro no cliente MQTT (tipo: %d, connect_return_code: %d).", etype, rcode);

            // CONNECTION_REFUSED_NOT_AUTHORIZED = 5, BAD_USERNAME = 4 → JWT invalido/expirado
            if (rcode == 4 || rcode == 5) {
                ESP_LOGW(TAG, "Auth rejeitada pelo broker — invalidando cache JWT.");
                AuthManager::invalidateCache();
            }

            s_is_connected = false;
            schedule_reconnect();
            break;
        }

        default:
            break;
    }
}

void MqttManager::init_mqtt(void) {

    if (mqtt_client != NULL) {
        ESP_LOGW(TAG, "MQTT client already initialized, ignoring.");
        return;
    }

    if (!AuthManager::isProvisioned()) {
        ESP_LOGE(TAG, "Device nao provisionado — sem credenciais Keycloak na NVS.");
        AppState::setError(
            ErrorCode::CERT_MISSING,   // reusa código existente pra "credencial ausente"
            "Device nao provisionado (credentials ausentes)",
            {TAG, "init_mqtt"}
        );
        return;
    }

    s_current_jwt = AuthManager::getJwt();
    if (s_current_jwt.empty()) {
        ESP_LOGE(TAG, "Falha ao obter JWT do Keycloak - abortando init MQTT");
        return;
    }

    s_device_id = AuthManager::getDeviceId();
    if (s_device_id.empty()) {
        ESP_LOGE(TAG, "deviceId ausente na NVS — abortando init MQTT");
        return;
    }

    ESP_LOGI(TAG, "JWT obtido (%zu chars) + deviceId=%s. Preparando conexao MQTT...",
             s_current_jwt.size(), s_device_id.c_str());

    // Cria o timer de reconexão (one-shot, rearmado manualmente)
    if (s_reconnect_timer == NULL) {
        esp_timer_create_args_t timer_args = {};
        timer_args.callback = reconnect_timer_cb;
        timer_args.name     = "mqtt_reconnect";
        esp_timer_create(&timer_args, &s_reconnect_timer);
    }

    esp_mqtt_client_config_t mqtt_cfg = {};
    mqtt_cfg.broker.address.uri = BROKER_URL;

    // Bundle mozzila CA
    mqtt_cfg.broker.verification.crt_bundle_attach = esp_crt_bundle_attach;

    mqtt_cfg.credentials.username                = s_current_jwt.c_str();
    mqtt_cfg.credentials.authentication.password = "unused";
    mqtt_cfg.credentials.client_id               = s_device_id.c_str();
    mqtt_cfg.session.disable_clean_session       = true;

    mqtt_cfg.buffer.size     = 4096;
    mqtt_cfg.buffer.out_size = 4096;

    // Inicializa e registra o event loop do MQTT
    mqtt_client = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(mqtt_client, (esp_mqtt_event_id_t)MQTT_EVENT_ANY, mqtt_event_handler, NULL);
    esp_mqtt_client_start(mqtt_client);

    // Timer de refresh preventivo do JWT.
    // Reconecta MQTT (TTL - MARGIN) segundos antes do JWT expirar.
    int remaining = AuthManager::getJwtRemainingSeconds();
    int refresh_at = remaining - CONFIG_GOV_KC_TOKEN_MARGIN_S;
    if (refresh_at < 30) refresh_at = 30;  // piso mínimo pra evitar hot loop

    if (s_refresh_timer == NULL) {
        esp_timer_create_args_t args = {};
        args.callback = refresh_timer_cb;
        args.name     = "jwt_refresh";
        esp_timer_create(&args, &s_refresh_timer);
    }
    esp_timer_stop(s_refresh_timer);
    esp_timer_start_periodic(s_refresh_timer, (uint64_t)refresh_at * 1000000ULL);
    ESP_LOGI(TAG, "Refresh preventivo de JWT agendado a cada %ds (TTL=%ds, MARGIN=%ds).",
             refresh_at, remaining, CONFIG_GOV_KC_TOKEN_MARGIN_S);
}

void MqttManager::publish(const uint8_t* data, size_t len, const char* topic, int qos) {
    if (mqtt_client == NULL) {
        ESP_LOGW(TAG, "publish() chamado antes de init_mqtt().");
        return;
    }

    int msg_id = esp_mqtt_client_publish(
        mqtt_client, topic, (const char*)data, (int)len, qos, 0);

    ESP_LOGI(TAG, "Publicado (msg_id=%d, %u bytes, qos=%d) no tópico [%s]",
             msg_id, (unsigned)len, qos, topic);
}

void MqttManager::subscribe(const std::string& topic, int qos) {

    if (mqtt_client == NULL) {
        ESP_LOGE(TAG, "subscribe() chamado antes de init_mqtt().");
        return;
    }

    for (const auto& [t, q] : s_subscriptions) {
        if (t == topic) {
            ESP_LOGW(TAG, "Tópico já registrado: %s", topic.c_str());
            return;
        }
    }
    s_subscriptions.emplace_back(topic, qos);

    int msg_id = esp_mqtt_client_subscribe(mqtt_client, topic.c_str(), qos);
    if (msg_id >= 0) {
        ESP_LOGI(TAG, "Inscrito no tópico: %s (msg_id=%d)", topic.c_str(), msg_id);
    } else {
        ESP_LOGE(TAG, "Falha ao inscrever no tópico: %s", topic.c_str());
        AppState::setError(
            ErrorCode::TOPIC_SUBSCRIBE_FAIL,
            "Falha ao subscrever: " + topic,
            {TAG, "subscribe"}
        );
    }
}

bool MqttManager::isConnected() {
    return s_is_connected;
}

void MqttManager::tryReconnect() {
    if (mqtt_client == NULL) return;
    s_reconnect_count = 0;
    schedule_reconnect();
}

void MqttManager::setCallback(MessageCallback cb) {
    s_message_callback = cb;
    ESP_LOGI(TAG, "Callback de mensagens MQTT registrado.");
}
