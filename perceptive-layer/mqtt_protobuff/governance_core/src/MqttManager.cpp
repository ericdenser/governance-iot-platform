#include "MqttManager.h"
#include "mqtt_client.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "AuthManager.h"
#include "AppState.h"
#include "esp_crt_bundle.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include <vector>
#include <utility>
#include <algorithm>

#define BROKER_URL     CONFIG_GOV_MQTT_BROKER_URI
#define MAX_RECONNECT_ATTEMPTS  5
#define RECONNECT_DELAY_MS      5000
// The JWT fetch is an HTTPS request (TLS handshake): too much for the esp_timer
// task stack, so the timers only wake this worker
#define WORKER_STACK_SIZE       8192
#define WORK_RECONNECT          (1 << 0)
#define WORK_REFRESH            (1 << 1)
// TLS needs a large contiguous block; below this, only a reboot defragments the heap
#define LOW_MEM_BLOCK_BYTES     (20 * 1024)
#define LOW_MEM_MAX_FAILS       3

static const char *TAG = "MqttManager";
esp_mqtt_client_handle_t mqtt_client = NULL;
static MqttManager::MessageCallback s_message_callback = nullptr;

static std::string s_current_jwt;
static std::string s_device_id;

static int                s_reconnect_count = 0;
static esp_timer_handle_t s_reconnect_timer = NULL;
static esp_timer_handle_t s_refresh_timer   = NULL;  // refresh JWT
static TaskHandle_t       s_worker_task     = NULL;
static bool               s_is_connected    = false;
static bool               s_error_reported  = false;
static bool               s_boot_audited    = false;
static bool               s_jwt_pending     = false;
static int                s_low_mem_fails   = 0;
static SemaphoreHandle_t  s_ack_sem         = NULL;
static volatile int       s_last_acked_id   = -1;

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


static void schedule_reconnect();

static void do_refresh() {
    if (mqtt_client == NULL) return;
    ESP_LOGI(TAG, "Safe JWT refresh — invalidating cache + reconnecting.");
    AuthManager::invalidateCache();
    if (mqtt_client) {
        esp_mqtt_client_disconnect(mqtt_client);
        // MQTT_EVENT_DISCONNECTED triggers schedule_reconnect -> reconnect_timer_cb
        // -> getJwt() new fetch (cache invalidated) -> CONNECT with new JWT.
    }
}

// Runs on the worker RECONNECT_DELAY_MS after a disconnect
static void do_reconnect() {
    if (mqtt_client == NULL) return;
    s_reconnect_count++;
    ESP_LOGW(TAG, "[%d/%d] Tentando reconectar ao broker MQTT...",
             s_reconnect_count, MAX_RECONNECT_ATTEMPTS);
    
    s_current_jwt = AuthManager::getJwt();
    if (s_current_jwt.empty()) {
        // any 8-bit heap: with PSRAM, mbedTLS allocates there
        size_t largest = heap_caps_get_largest_free_block(MALLOC_CAP_8BIT);
        ESP_LOGE(TAG, "JWT vazio no reconnect (heap livre %u, maior bloco %u) - nova tentativa agendada.",
                 (unsigned)esp_get_free_heap_size(), (unsigned)largest);
        if (largest < LOW_MEM_BLOCK_BYTES && ++s_low_mem_fails >= LOW_MEM_MAX_FAILS) {
            ESP_LOGE(TAG, "Sem memoria para TLS apos %d tentativas. Reiniciando.", s_low_mem_fails);
            esp_restart();
        }
        schedule_reconnect();
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
    cfg.network.disable_auto_reconnect = true;
    esp_mqtt_set_config(mqtt_client, &cfg);

    esp_mqtt_client_reconnect(mqtt_client);
}

static void mqtt_worker_task(void*) {
    for (;;) {
        uint32_t work = 0;
        xTaskNotifyWait(0, UINT32_MAX, &work, portMAX_DELAY);
        if (work & WORK_REFRESH)   do_refresh();
        if (work & WORK_RECONNECT) do_reconnect();
    }
}

static void reconnect_timer_cb(void*) {
    if (s_worker_task) xTaskNotify(s_worker_task, WORK_RECONNECT, eSetBits);
}

static void refresh_timer_cb(void*) {
    if (s_worker_task) xTaskNotify(s_worker_task, WORK_REFRESH, eSetBits);
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
            s_low_mem_fails   = 0;
            s_error_reported  = false;
            esp_timer_stop(s_reconnect_timer);
            // primeira conexao do boot sempre passa pelo BOOT_AUDIT (subscribe + validacao do fw),
            // mesmo que o broker tenha caido antes e o estado ja esteja em ERROR
            if (!s_boot_audited) {
                s_boot_audited = true;
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

        case MQTT_EVENT_PUBLISHED:
            s_last_acked_id = event->msg_id;
            if (s_ack_sem) xSemaphoreGive(s_ack_sem);
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
        // Keycloak fora: o tryReconnect chama o init_mqtt de novo
        ESP_LOGE(TAG, "Falha ao obter JWT do Keycloak - nova tentativa no tryReconnect");
        s_jwt_pending = true;
        return;
    }
    s_jwt_pending = false;

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
    if (s_worker_task == NULL) {
        xTaskCreate(mqtt_worker_task, "mqtt_worker", WORKER_STACK_SIZE, NULL, 5, &s_worker_task);
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

    // Reconnects only through the worker, which fetches a fresh JWT first. The
    // built-in retry would reuse the expired one every 10s (broker answers 5) and
    // keep a TLS session open while the token request needs its own (OOM).
    mqtt_cfg.network.disable_auto_reconnect = true;

    // Inicializa e registra o event loop do MQTT
    mqtt_client = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(mqtt_client, (esp_mqtt_event_id_t)MQTT_EVENT_ANY, mqtt_event_handler, NULL);
    esp_mqtt_client_start(mqtt_client);

    // Timer de refresh preventivo do JWT.
    // Margem efetiva = min(MARGIN configurado, 20% do TTL). Isso garante que
    int remaining = AuthManager::getJwtRemainingSeconds();
    int margin_eff = std::min(CONFIG_GOV_KC_TOKEN_MARGIN_S, remaining / 5);
    if (margin_eff < 30) margin_eff = 30;
    int refresh_at = remaining - margin_eff;
    if (refresh_at < 60) refresh_at = 60;  // piso: nunca refresh sub-1min

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

bool MqttManager::publishConfirmed(const uint8_t* data, size_t len, const char* topic, int timeout_ms) {
    if (mqtt_client == NULL || !s_is_connected) return false;
    if (s_ack_sem == NULL) s_ack_sem = xSemaphoreCreateBinary();
    xSemaphoreTake(s_ack_sem, 0);

    int msg_id = esp_mqtt_client_publish(mqtt_client, topic, (const char*)data, (int)len, 1, 0);
    if (msg_id <= 0) return false;

    // the PUBACK may arrive before this point, and other QoS 1 publishes also signal
    int64_t deadline = esp_timer_get_time() + (int64_t)timeout_ms * 1000;
    while (s_last_acked_id != msg_id) {
        int64_t left_ms = (deadline - esp_timer_get_time()) / 1000;
        if (left_ms <= 0) {
            ESP_LOGW(TAG, "Sem PUBACK para msg_id=%d em %d ms [%s]", msg_id, timeout_ms, topic);
            return false;
        }
        xSemaphoreTake(s_ack_sem, pdMS_TO_TICKS(left_ms));
    }
    ESP_LOGI(TAG, "Publicado e confirmado (msg_id=%d, %u bytes, qos=1) no tópico [%s]",
             msg_id, (unsigned)len, topic);
    return true;
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
    if (mqtt_client == NULL) {
        // client nao foi criado por falta de JWT no init
        if (s_jwt_pending) init_mqtt();
        return;
    }
    s_reconnect_count = 0;
    schedule_reconnect();
}

void MqttManager::setCallback(MessageCallback cb) {
    s_message_callback = cb;
    ESP_LOGI(TAG, "Callback de mensagens MQTT registrado.");
}

void MqttManager::destroy() {
    
    if (mqtt_client == NULL) { return; } 

    if (s_reconnect_timer) {
        esp_timer_stop(s_reconnect_timer);
        esp_timer_delete(s_reconnect_timer);
        s_reconnect_timer = NULL;
    }
    if (s_refresh_timer) {
        esp_timer_stop(s_refresh_timer);
        esp_timer_delete(s_refresh_timer);
        s_refresh_timer = NULL;
    }
    esp_mqtt_client_stop(mqtt_client);
    esp_mqtt_client_destroy(mqtt_client);
    mqtt_client = NULL;
    ESP_LOGI(TAG, "MQTT_CLIENT destruído.");
}
