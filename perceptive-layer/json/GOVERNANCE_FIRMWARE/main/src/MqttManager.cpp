#include "MqttManager.h"
#include "mqtt_client.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "driver/gpio.h"
#include "CryptoManager.h"
#include "AppState.h"

#define BROKER_URL              "mqtts://192.168.15.76:8883"
#define MAX_RECONNECT_ATTEMPTS  5
#define RECONNECT_DELAY_MS      5000

extern "C" {
    extern const uint8_t rootCA_crt_start[] asm("_binary_rootCA_crt_start");
    extern const uint8_t rootCA_crt_end[]   asm("_binary_rootCA_crt_end");
}

static const char *TAG = "MqttManager";
esp_mqtt_client_handle_t mqtt_client = NULL;
static MqttManager::MessageCallback s_message_callback = nullptr;

static int                s_reconnect_count = 0;
static esp_timer_handle_t s_reconnect_timer = NULL;

// Chamado pelo timer após RECONNECT_DELAY_MS para tentar reconectar
static void reconnect_timer_cb(void*) {
    s_reconnect_count++;
    ESP_LOGW(TAG, "[%d/%d] Tentando reconectar ao broker MQTT...",
             s_reconnect_count, MAX_RECONNECT_ATTEMPTS);
    esp_mqtt_client_reconnect(mqtt_client);
}

// Agenda próxima tentativa ou seta erro se esgotou as tentativas
static void schedule_reconnect() {
    if (s_reconnect_count >= MAX_RECONNECT_ATTEMPTS) {
        ESP_LOGE(TAG, "Broker inacessível após %d tentativas. Setando erro.", MAX_RECONNECT_ATTEMPTS);
        AppState::setError(
            ErrorCode::MQTT_DISCONNECTED,
            "Broker MQTT inacessível após 5 tentativas de reconexão",
            {TAG, "schedule_reconnect"}
        );
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
            s_reconnect_count = 0;
            esp_timer_stop(s_reconnect_timer);
            AppState::transition(DeviceState::BOOT_AUDIT, {TAG, "mqtt_event_handler"});
            break;

        case MQTT_EVENT_DISCONNECTED:
            ESP_LOGW(TAG, "Desconectado do Broker MQTT.");
            schedule_reconnect();
            break;

        case MQTT_EVENT_DATA: {
            std::string topic(event->topic, event->topic_len);
            std::string payload(event->data, event->data_len);

            ESP_LOGI(TAG, "Comando recebido:");
            printf("Tópico: %.*s\r\n", event->topic_len, event->topic);
            printf("Payload: %.*s\r\n", event->data_len, event->data);

            if (s_message_callback) {
                s_message_callback(topic, payload);
            }
            break;
        }

        case MQTT_EVENT_ERROR:
            // Falha de TCP durante reconexão — agenda próxima tentativa
            ESP_LOGE(TAG, "Erro no cliente MQTT (tipo: %d).",
                     event->error_handle ? event->error_handle->error_type : -1);
            schedule_reconnect();
            break;

        default:
            break;
    }
}

void MqttManager::init_mqtt(void) {

    if (mqtt_client != NULL) {
        ESP_LOGW(TAG, "MQTT client already initialized, ignoring.");
        return;
    }

    static std::string client_cert = CryptoManager::getCertificate();
    static std::string private_key = CryptoManager::getPrivateKey();

    if (client_cert.empty()) {
        ESP_LOGE(TAG, "ERRO: Certificado nao encontrado na NVS!");
        AppState::setError(
            ErrorCode::CERT_MISSING,
            "Certificado ausente na NVS. Device precisa ser provisionado.",
            {TAG, "init_mqtt"}
        );
        return;
    }

    if (private_key.empty()) {
        ESP_LOGE(TAG, "ERRO: Chave Privada nao encontrada na NVS!");
        AppState::setError(
            ErrorCode::KEY_MISSING,
            "Chave privada ausente na NVS. Device precisa ser reciclado.",
            {TAG, "init_mqtt"}
        );
        return;
    }

    ESP_LOGI(TAG, "Identidade criptografica carregada: \n%.50s...", client_cert.c_str());

    // Cria o timer de reconexão (one-shot, rearmado manualmente)
    if (s_reconnect_timer == NULL) {
        esp_timer_create_args_t timer_args = {};
        timer_args.callback = reconnect_timer_cb;
        timer_args.name     = "mqtt_reconnect";
        esp_timer_create(&timer_args, &s_reconnect_timer);
    }

    esp_mqtt_client_config_t mqtt_cfg = {};
    mqtt_cfg.broker.address.uri = BROKER_URL;

    mqtt_cfg.broker.verification.certificate              = (const char*)rootCA_crt_start;
    mqtt_cfg.broker.verification.skip_cert_common_name_check = true;

    mqtt_cfg.credentials.authentication.certificate = client_cert.c_str();
    mqtt_cfg.credentials.authentication.key         = private_key.c_str();

    // Desabilita auto-reconexão — gerenciamos manualmente para controlar o número de tentativas
    mqtt_cfg.network.disable_auto_reconnect = true;

    mqtt_client = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(mqtt_client, (esp_mqtt_event_id_t)MQTT_EVENT_ANY, mqtt_event_handler, NULL);
    esp_mqtt_client_start(mqtt_client);
}

void MqttManager::publish(const char* payload, const char* topic) {
    if (mqtt_client != NULL) {
        int msg_id = esp_mqtt_client_publish(mqtt_client, topic, payload, 0, 0, 0);
        ESP_LOGI(TAG, "Publicado (msg_id=%d) no topico %s: %s", msg_id, topic, payload);
    }
    vTaskDelay(5000 / portTICK_PERIOD_MS);
}

void MqttManager::subscribe(const std::string& topic, int qos) {
    if (mqtt_client != NULL) {
        int msg_id = esp_mqtt_client_subscribe(mqtt_client, topic.c_str(), qos);
        if (msg_id >= 0) {
            ESP_LOGI(TAG, "Inscrito no tópico: %s (msg_id=%d)", topic.c_str(), msg_id);
        } else {
            ESP_LOGE(TAG, "Falha ao se inscrever no tópico: %s", topic.c_str());
            AppState::setError(
                ErrorCode::TOPIC_SUBSCRIBE_FAIL,
                "Failed to subscribe on Topic: " + topic,
                {TAG, "subscribe"}
            );
        }
    } else {
        ESP_LOGE(TAG, "Cliente MQTT não inicializado. Impossível inscrever.");
    }
}

void MqttManager::setCallback(MessageCallback cb) {
    s_message_callback = cb;
    ESP_LOGI(TAG, "Callback de mensagens MQTT registrado.");
}
