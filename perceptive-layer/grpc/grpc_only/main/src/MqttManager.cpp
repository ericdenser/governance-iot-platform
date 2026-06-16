#include "MqttManager.h"
#include "mqtt_client.h"
#include "esp_log.h"
#include "AppState.h"
#include "driver/gpio.h"
#include "CryptoManager.h"

#define BROKER_URL "mqtts://172.16.39.40:8883"

extern "C" {
    extern const uint8_t rootCA_crt_start[] asm("_binary_rootCA_crt_start");
    extern const uint8_t rootCA_crt_end[]   asm("_binary_rootCA_crt_end");
}

static const char* TAG = "MqttManager";
static esp_mqtt_client_handle_t mqtt_client = NULL;
static MqttManager::MessageCallback s_message_callback = nullptr;

static void mqtt_event_handler(void* handler_args, esp_event_base_t base,
                                int32_t event_id, void* event_data)
{
    esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;

    switch ((esp_mqtt_event_id_t)event_id) {

        case MQTT_EVENT_CONNECTED:
            ESP_LOGI(TAG, "Conectado ao broker MQTT.");
            AppState::transition(DeviceState::VERIFY_ROLLBACK, {TAG, "mqtt_event_handler"});
            break;

        case MQTT_EVENT_DISCONNECTED:
            ESP_LOGW(TAG, "Desconectado do broker MQTT.");
            AppState::setError(
                ErrorCode::MQTT_DISCONNECTED,
                "Conexão com broker MQTT perdida",
                {TAG, "mqtt_event_handler"}
            );
            break;

        case MQTT_EVENT_DATA: {
            std::string topic(event->topic, event->topic_len);
            std::string payload(event->data, event->data_len);

            ESP_LOGI(TAG, "Comando recebido — tópico: %.*s (%d bytes)",
                     event->topic_len, event->topic, event->data_len);

            if (s_message_callback) {
                s_message_callback(topic, payload);
            }
            break;
        }

        case MQTT_EVENT_ERROR:
            ESP_LOGE(TAG, "Erro no cliente MQTT.");
            break;

        default:
            break;
    }
}

void MqttManager::init_mqtt(void)
{
    static std::string client_cert = CryptoManager::getCertificate();
    static std::string private_key = CryptoManager::getPrivateKey();

    if (client_cert.empty()) {
        ESP_LOGE(TAG, "ERRO: Certificado nao encontrados na NVS! O dispositivo precisa ser provisionado.");
        std::string msgOut = "ERRO: Certificado nao encontrados na NVS! O dispositivo precisa ser provisionado";
        AppState::setError(
            ErrorCode::CERT_MISSING, 
            msgOut, 
            {TAG, "mqtt_event_handler"}
        );
        return;
    }

    if (private_key.empty()) {
        ESP_LOGE(TAG, "ERRO: Chave Privada nao encontrada na NVS! O dispositivo precisa ser reciclado.");
        std::string msgOut = "ERRO: Chave Privada nao encontrada na NVS! O dispositivo precisa ser reciclado.";
        AppState::setError(
            ErrorCode::KEY_MISSING, 
            msgOut, 
            {TAG, "mqtt_event_handler"}
        );
        return;
    }

    // imprime o começo do certificado (debug)
    ESP_LOGI(TAG, "Lido da NVS: \n%.50s...", client_cert.c_str());

    ESP_LOGI(TAG, "Identidade criptografica carregada. Preparando conexao MQTTS...");

    esp_mqtt_client_config_t mqtt_cfg = {};
    mqtt_cfg.broker.address.uri = BROKER_URL;

    mqtt_cfg.broker.verification.certificate = (const char*)rootCA_crt_start;

    mqtt_cfg.credentials.authentication.certificate = client_cert.c_str(); 
    mqtt_cfg.credentials.authentication.key = private_key.c_str();

    mqtt_cfg.broker.verification.skip_cert_common_name_check = true;


    // Inicializa e registra o event loop do MQTT
    mqtt_client = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(mqtt_client, (esp_mqtt_event_id_t)MQTT_EVENT_ANY, mqtt_event_handler, NULL);
    esp_mqtt_client_start(mqtt_client);
}
void MqttManager::publish(const uint8_t* data, size_t len, const char* topic)
{
    if (mqtt_client == NULL) {
        ESP_LOGW(TAG, "publish() chamado antes de init_mqtt().");
        return;
    }

    /* Passa len explicitamente — sem isso, esp_mqtt usaria strlen e quebraria
     * em payloads binários que contêm bytes 0x00. */
    int msg_id = esp_mqtt_client_publish(
        mqtt_client, topic, (const char*)data, (int)len, 0, 0);

    ESP_LOGI(TAG, "Publicado (msg_id=%d, %u bytes) no tópico [%s]",
             msg_id, (unsigned)len, topic);

    vTaskDelay(pdMS_TO_TICKS(5000));
}

void MqttManager::subscribe(const std::string& topic, int qos)
{
    if (mqtt_client == NULL) {
        ESP_LOGE(TAG, "subscribe() chamado antes de init_mqtt().");
        return;
    }

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

void MqttManager::setCallback(MessageCallback cb)
{
    s_message_callback = cb;
    ESP_LOGI(TAG, "Callback de mensagens MQTT registrado.");
}
