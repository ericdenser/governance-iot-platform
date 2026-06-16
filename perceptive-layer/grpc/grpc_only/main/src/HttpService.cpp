#include "HttpService.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_crt_bundle.h" 
#include "AppState.h"

static const char *TAG = "HttpService";

// Event handler 
static esp_err_t http_event_handler(esp_http_client_event_t *evt) {
    switch(evt->event_id) {
        case HTTP_EVENT_ON_DATA:
            if (evt->user_data != NULL && evt->data_len > 0) {
                std::string* buf = static_cast<std::string*>(evt->user_data);
                // Anexa apenas o tamanho exato de bytes recebidos, evitando ler "lixo"
                buf->append((char*)evt->data, evt->data_len);
            }
            break;
        default:
            break;
    }
    return ESP_OK;
}

// GET
bool HttpService::get(const std::string &url, std::string &response_buffer, std::string &msgOut, const char* authorization) {
    return perform_request(url, HTTP_METHOD_GET, "", response_buffer, msgOut, NULL, authorization);
}

// POST
bool HttpService::post(const std::string &url, const std::string &payload, std::string &response_buffer, std::string &msgOut, const char* content_type, const char* authorization) {
    return perform_request(url, HTTP_METHOD_POST, payload, response_buffer, msgOut, content_type, authorization);
}

// LÓGICA CENTRAL
bool HttpService::perform_request(const std::string &url, esp_http_client_method_t method, const std::string &payload, std::string &response_buffer, std::string &msgOut, const char* content_type, const char* authorization) {
    
    AppState::transition(DeviceState::HTTP_INIT, {TAG, "perform_request"});

    // Limpa o buffer de resposta ANTES de cada requisição
    response_buffer.clear();

    esp_http_client_config_t config = {};
    config.url = url.c_str();
    config.event_handler = http_event_handler;
    config.user_data = &response_buffer;
    config.buffer_size = 4096;
    config.buffer_size_tx = 4096;
    config.timeout_ms = 10000;

    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (client == NULL) {
        msgOut = "Falha ao inicializar cliente HTTP";
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        AppState::setError(
            ErrorCode::HTTP_INIT_FAIL, 
            msgOut, 
            {TAG, "perform_request"}
        );
        return false;
    }

    esp_http_client_set_method(client, method);

    if (method == HTTP_METHOD_POST || method == HTTP_METHOD_PUT) {
        // Envia o payload exato
        esp_http_client_set_post_field(client, payload.c_str(), payload.length());
        
        if (content_type != NULL) {
            esp_http_client_set_header(client, "Content-Type", content_type);
        } else {
            esp_http_client_set_header(client, "Content-Type", "application/json");
        }
    }

    if (authorization != NULL) {
        esp_http_client_set_header(client, "Authorization", authorization);
    }

    // Executa
    esp_err_t err = esp_http_client_perform(client);
    AppState::transition(DeviceState::HTTP_REQUEST, {TAG, "perform_request"});
    bool success = false;

    if (err == ESP_OK) {
        int status = esp_http_client_get_status_code(client);
        
        // Se a resposta for 200, 201 ou 202, assumimos SUCESSO
        if(status >= 200 && status < 300) {
            success = true;
            msgOut = "Request Successful (Code: " + std::to_string(status) + ")" + "Response Buffer:" + response_buffer.c_str();
            ESP_LOGI(TAG, "%s", msgOut.c_str());
        } else {
            msgOut = "HTTP Code: " + std::to_string(status);
            ESP_LOGE(TAG, "%s", msgOut.c_str());
            // Imprime a resposta do erro que o servidor mandou 
            ESP_LOGE(TAG, "Corpo do Erro retornado pela API: %s", response_buffer.c_str());

            AppState::setError(
                ErrorCode::HTTP_REQUEST_FAIL, 
                msgOut, 
                {TAG, "perform_request"}
            );
            
        }
    } else {
        msgOut = "Falha no request HTTP: " + std::string(esp_err_to_name(err));
        ESP_LOGE(TAG, "%s", msgOut.c_str());

        AppState::setError(
            ErrorCode::HTTP_REQUEST_FAIL, 
            msgOut, 
            {TAG, "perform_request"}
        );
    }

    esp_http_client_cleanup(client);
    return success;
}