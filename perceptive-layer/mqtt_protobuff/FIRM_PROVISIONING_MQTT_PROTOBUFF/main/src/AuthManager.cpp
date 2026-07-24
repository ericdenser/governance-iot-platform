#include "AuthManager.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_attr.h"
#include "cJSON.h"
#include "freertos/FreeRTOS.h"
#include "AppState.h"
#include "HttpService.h"
#include "esp_system.h"
#include "freertos/task.h"
#include <string.h>
#include <time.h>


static const char* TAG = "AuthManager";
static const char* NVS_NAMESPACE = "crypto_store";

#define JWT_CACHE_SIZE 3072
RTC_DATA_ATTR static char   s_jwt[JWT_CACHE_SIZE];
RTC_DATA_ATTR static size_t s_jwt_len    = 0;   // 0 = cache invalido
RTC_DATA_ATTR static time_t s_expires_at = 0;   // epoch de expiracao

bool AuthManager::isProvisioned() {
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) return false;
    size_t sz = 0;
    esp_err_t err = nvs_get_str(h, "client_id", NULL, &sz);
    nvs_close(h);
    return (err == ESP_OK && sz > 0);
}

bool AuthManager::handleProvisioningResponse(const std::string& responseBuffer, std::string& msgOut) { 
    cJSON* root = cJSON_Parse(responseBuffer.c_str());
    if (root == NULL) {
        msgOut = "Provisioning response is not valid JSON.";
        AppState::setError(ErrorCode::PROVISIONING_RESPONSE_INVALID, msgOut, {TAG, "handleProvisioningResponse"});
        return false;
    }

    auto cleanup = [&]() { if (root) cJSON_Delete(root); };

    cJSON* successNode = cJSON_GetObjectItem(root, "success");
    if (!cJSON_IsTrue(successNode)) {
        msgOut = "Provisioning response: success != true. ";
        AppState::setError(ErrorCode::PROVISIONING_RESPONSE_INVALID, msgOut, {TAG, "handleProvisioningResponse"});
        cleanup();
        return false;
    }

    cJSON* dataNode = cJSON_GetObjectItem(root, "data");
    if (!cJSON_IsObject(dataNode)) {
        msgOut = "Provisioning response: data field is not an object";
        AppState::setError(ErrorCode::PROVISIONING_RESPONSE_INVALID, msgOut, {TAG, "handleProvisioningResponse"});
        cleanup();
        return false;
    }

    cJSON* clientIdNode = cJSON_GetObjectItem(dataNode, "clientId");
    cJSON* secretNode = cJSON_GetObjectItem(dataNode, "clientSecret");
    if (!cJSON_IsString(clientIdNode) || !cJSON_IsString(secretNode)) {
        msgOut = "Provisioning response: missing clientId or clientSecret";
        AppState::setError(ErrorCode::PROVISIONING_RESPONSE_INVALID, msgOut, {TAG, "handleProvisioningResponse"});
        cleanup();
        return false;
    }

    

    std::string clientId = clientIdNode->valuestring;
    std::string clientSecret = secretNode->valuestring;

    if (!saveCredentials(clientId, clientSecret)) {
        msgOut = "Failed to save Keycloak credentials to NVS.";
        cleanup();
        return false; // saveCredentials already set AppState
    }

    // erase provisioning token
    nvs_handle_t h;
    if(nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        nvs_erase_key(h, "prov_token");
        nvs_commit(h);
        nvs_close(h);
        ESP_LOGI(TAG, "Provisioning token erased from NVS");
    }

    ESP_LOGI(TAG, "Keycloak credentials saved. Reboot in 2s...");
    cleanup();

    vTaskDelay(pdMS_TO_TICKS(2000));
    esp_restart();
    return true; // unreachable
    
}

void AuthManager::invalidateCache() {
    s_jwt_len = 0;
    s_expires_at = 0;
    ESP_LOGI(TAG, "JWT cache invalidated");
}

int AuthManager::getJwtRemainingSeconds() {
    if (s_jwt_len == 0 || s_expires_at == 0) return 0;
    int remaining = (int)(s_expires_at - time(NULL));
    return remaining > 0 ? remaining : 0;
}

std::string AuthManager::getDeviceId() {
    return loadFromNVS("client_id");
}

std::string AuthManager::getJwt() {
    time_t now = time(NULL);

    // Safeguard: se o cache mostra tempo fora da curva, invalida e força fetch novo.
    const time_t MAX_SANE_TTL = 24 * 3600;   // 24h
    if (s_expires_at > 0 && (s_expires_at - now) > MAX_SANE_TTL) {
        ESP_LOGW(TAG, "Cache com TTL absurdo (%llds) — provavel RTC reset. Invalidando.",
                 (long long)(s_expires_at - now));
        s_jwt_len = 0;
        s_expires_at = 0;
    }

    if (s_jwt_len > 0 && s_expires_at > 0 &&
        (s_expires_at - now) > CONFIG_GOV_KC_TOKEN_MARGIN_S) {
        return std::string(s_jwt, s_jwt_len);
    }

    std::string newJwt;
    int expiresIn = 0;
    std::string err;
    if (!fetchJwt(newJwt, expiresIn, err)) {
        AppState::setError(ErrorCode::HTTP_REQUEST_FAIL, err, {TAG, "getJwt"});
        return "";
    }

    // Copia pro buffer estatico (RTC RAM). Trunca se exceder.
    if (newJwt.length() >= JWT_CACHE_SIZE) {
        ESP_LOGE(TAG, "JWT (%zu bytes) exceeds buffer (%d bytes)",
                 newJwt.length(), JWT_CACHE_SIZE);
        AppState::setError(ErrorCode::MEMORY_ALOCATION_FAIL,
                           "JWT too large for RTC cache",
                           {TAG, "getJwt"});
        return "";
    }
    memcpy(s_jwt, newJwt.data(), newJwt.length());
    s_jwt[newJwt.length()] = '\0';
    s_jwt_len    = newJwt.length();
    s_expires_at = time(NULL) + expiresIn;
    ESP_LOGI(TAG, "JWT renovado (expires_in=%ds)", expiresIn);
    return std::string(s_jwt, s_jwt_len);
}

bool AuthManager::saveCredentials(const std::string& clientId, const std::string& clientSecret) {
    return saveToNVS("client_id", clientId) && saveToNVS("client_secret", clientSecret);
}

bool AuthManager::saveToNVS(const char* key, const std::string& data) {
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        std::string m = "NVS open failed [key=" + std::string(key) + "]";
        AppState::setError(ErrorCode::NVS_INIT_FAIL, m, {TAG, "saveCredentials"});
        return false;
    }

    if(nvs_set_str(h, key, data.c_str()) != ESP_OK) {
        std::string m = "NVS set failed [key= " + std::string(key) + "]";
        AppState::setError(ErrorCode::NVS_WRITE_FAIL, m, {TAG, "saveCredentials"});
        nvs_close(h);
        return false;
    }
    
    if (nvs_commit(h) != ESP_OK) {
        std::string m = "NVS commit failed [key= " + std::string(key) + "]";
        AppState::setError(ErrorCode::NVS_COMMIT_FAIL, m, {TAG, "saveCredentials"});
        nvs_close(h);
        return false;
    }

    nvs_close(h);
    return true;
}

std::string AuthManager::loadFromNVS(const char* key) {
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) return "";
    size_t sz = 0;
    if (nvs_get_str(h, key, NULL, &sz) != ESP_OK || sz == 0) {
        nvs_close(h);
        std::string m = "NVS failed to load [key=" + std::string(key) + "]";
        AppState::setError(ErrorCode::NVS_LOAD_FAIL, m, {TAG, "loadFromNvs"});
        return "";
    }

    char* buf = new char[sz];
    nvs_get_str(h, key, buf, &sz);
    std::string result(buf);
    delete[] buf;
    nvs_close(h);
    return result;

}


bool AuthManager::fetchJwt(std::string& jwtOut, int& expiresInOut, std::string& errOut) {
    std::string clientId = loadFromNVS("client_id");
    std::string secret = loadFromNVS("client_secret");
    if (clientId.empty() || secret.empty()) {
        errOut = "client_id or client_secret missing in NVS";
        return false;
    }

    std::string url = std::string(CONFIG_GOV_KEYCLOAK_URL)
                    + "/realms/" + CONFIG_GOV_KEYCLOAK_REALM
                    + "/protocol/openid-connect/token";

    std::string body = "grant_type=client_credentials" 
                    + std::string("&client_id=") + clientId
                    + std::string("&client_secret=") + secret;

    std::string responseBuf;

    if (!HttpService::post(url, body, responseBuf, errOut,
         "application/x-www-form-urlencoded", NULL)) {
        return false; // HttpService already set AppState
    }

    cJSON* root = cJSON_Parse(responseBuf.c_str());
    if (!root) { errOut = "Keycloak /token response is not a valid JSON"; return false; }

    auto cleanup = [&]() { if (root) cJSON_Delete(root); };

    cJSON* tokenNode = cJSON_GetObjectItem(root, "access_token");
    cJSON* expiresNode = cJSON_GetObjectItem(root, "expires_in");
    if (!cJSON_IsString(tokenNode) || !cJSON_IsNumber(expiresNode)) {
        errOut = "Keycloak /token response missing access_token or expires_in";
        cleanup();
        return false;
    }

    jwtOut = tokenNode->valuestring;
    expiresInOut = expiresNode->valueint;
    cleanup();
    return true;

}