#include "CaptivePortal.h"
#include "esp_http_server.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "mdns.h"
#include "esp_log.h"
#include <stdlib.h>
#include <string.h>

static httpd_handle_t server = NULL;

// Browsers send form data as application/x-www-form-urlencoded:
// spaces → '+', special chars → '%XX'. httpd_query_key_value does NOT decode,
// so we must decode manually before saving to NVS.
static void url_decode(const char *src, char *dst, size_t dst_len) {
    size_t i = 0, j = 0;
    while (src[i] != '\0' && j < dst_len - 1) {
        if (src[i] == '+') {
            dst[j++] = ' ';
            i++;
        } else if (src[i] == '%' && src[i + 1] != '\0' && src[i + 2] != '\0') {
            char hex[3] = {src[i + 1], src[i + 2], '\0'};
            dst[j++] = (char)strtol(hex, NULL, 16);
            i += 3;
        } else {
            dst[j++] = src[i++];
        }
    }
    dst[j] = '\0';
}

const char* html_page = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Setup Mackenzie IoT</title>
<style>
body { font-family: Arial, sans-serif; text-align: center; margin-top: 40px; background-color: #f4f4f9; }
.container { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.1); display: inline-block; width: 90%; max-width: 350px; }
input { width: 90%; padding: 10px; margin: 10px 0; border: 1px solid #ccc; border-radius: 4px; }
button { width: 95%; padding: 12px; background-color: #cc0000; color: white; border: none; border-radius: 4px; font-weight: bold; cursor: pointer; }
</style>
</head>
<body>
<div class="container">
<h2>Setup do Dispositivo</h2>
<form action="/configure" method="POST">
<input type="text" name="ssid" placeholder="Rede Wi-Fi" required><br>
<input type="password" name="password" placeholder="Senha da Rede" required><br>
<input type="text" name="token" placeholder="Token de Provisionamento" required><br>
<button type="submit">Ativar Dispositivo</button>
</form>
</div>
</body>
</html>
)rawliteral";

static void save_credential_nvs(const char* key, const char* value) {
    nvs_handle_t handle;
    if (nvs_open("crypto_store", NVS_READWRITE, &handle) == ESP_OK) {
        nvs_set_str(handle, key, value);
        nvs_commit(handle);
        nvs_close(handle);
    }
}

static esp_err_t root_get_handler(httpd_req_t *req) {
    httpd_resp_send(req, html_page, HTTPD_RESP_USE_STRLEN);
    return ESP_OK;
}

static esp_err_t configure_post_handler(httpd_req_t *req) {
    char buf[512];
    size_t recv_len = req->content_len < sizeof(buf) - 1 ? req->content_len : sizeof(buf) - 1;
    int ret = httpd_req_recv(req, buf, recv_len);
    if (ret <= 0) {
        return ESP_FAIL;
    }
    buf[ret] = '\0';

    char ssid_raw[64] = {0};
    char pass_raw[64] = {0};
    char token_raw[64] = {0};

    httpd_query_key_value(buf, "ssid",     ssid_raw,  sizeof(ssid_raw));
    httpd_query_key_value(buf, "password", pass_raw,  sizeof(pass_raw));
    httpd_query_key_value(buf, "token",    token_raw, sizeof(token_raw));

    char ssid[64]  = {0};
    char pass[64]  = {0};
    char token[64] = {0};
    url_decode(ssid_raw,  ssid,  sizeof(ssid));
    url_decode(pass_raw,  pass,  sizeof(pass));
    url_decode(token_raw, token, sizeof(token));

    save_credential_nvs("wifi_ssid", ssid);
    save_credential_nvs("wifi_pass", pass);
    save_credential_nvs("prov_token", token);

    const char* resp = "Credenciais recebidas! O dispositivo sera reiniciado.";
    httpd_resp_send(req, resp, HTTPD_RESP_USE_STRLEN);

    vTaskDelay(pdMS_TO_TICKS(2000));
    esp_restart();

    return ESP_OK;
}

void CaptivePortal::start() {
    esp_err_t err = mdns_init();
    if (err == ESP_OK) {
        // Ex "setup", endpoint = http://setup.local no navegador.
        mdns_hostname_set("setup"); 
        
        // Nome que aparece em scanners de rede
        mdns_instance_name_set("Setup Mackenzie IoT"); 
        
        ESP_LOGI("CAPTIVE", "mDNS iniciado com sucesso! Acesse: http://setup.local");
    } else {
        ESP_LOGE("CAPTIVE", "Falha ao iniciar o mDNS");
    }

    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    if (httpd_start(&server, &config) == ESP_OK) {
        httpd_uri_t root = {
            .uri       = "/",
            .method    = HTTP_GET,
            .handler   = root_get_handler,
            .user_ctx  = NULL 
        };
        httpd_register_uri_handler(server, &root);

        httpd_uri_t configure = {
            .uri       = "/configure",
            .method    = HTTP_POST,
            .handler   = configure_post_handler,
            .user_ctx  = NULL 
        };
        httpd_register_uri_handler(server, &configure);
    }
}

void CaptivePortal::stop() {
    if (server) {
        httpd_stop(server);
        server = NULL;
    }
}