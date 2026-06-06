#include "esp_log.h"
#include "CommandProcessor.h"
#include <string>
#include "cJSON.h"
#include "AppState.h"
#include <unordered_map>
#include <algorithm>
#include "nvs.h"
#include <cctype>
#include "OtaManager.h"
#include "WatchdogManager.h"
#include "esp_task_wdt.h"
#include "freertos/FreeRTOS.h" 
#include "freertos/task.h"

static const char* TAG = "CommandProcessor";

struct OtaTaskParams {
    float newVersion;
    std::string url_bin;
};

// =============================================================================
//  HELPER FUNCTIONS
// =============================================================================
static void nvs_read_float(const char* ns, const char* key, const float* result) {
    nvs_handle_t h;

    uint32_t aux;

    if (nvs_open(ns, NVS_READONLY, &h) != ESP_OK) return;

    esp_err_t err = nvs_get_u32(h, key, &aux);
    if (err == ESP_OK) {
        memcpy((void*)result, &aux, sizeof(aux));
    }
    nvs_close(h);
}

// get string -> enum
static CommandType getCommandType(std::string cmd) {

    std::transform(cmd.begin(), cmd.end(), cmd.begin(),
        [](unsigned char c){ return std::tolower(c); });

    static const std::unordered_map<std::string, CommandType> commandMap = {
        {"update",    CommandType::UPDATE},
        {"sleep",  CommandType::SLEEP},
        {"reboot", CommandType::REBOOT}
    };

    auto it = commandMap.find(cmd);

    if (it != commandMap.end()) {
        return it->second;
    }

    return CommandType::UNKNOWN;
}


static void ota_task_routine(void* pvParameters) {

    OtaTaskParams* params = (OtaTaskParams*)pvParameters;

    std::string msgOut;
    WatchdogManager::addToCurrentTask();

    bool success = OtaManager::verify_and_update(
        params->newVersion,
        params->url_bin,
        msgOut,
        []{ WatchdogManager::reset(); }
    );

    WatchdogManager::removeFromCurrentTask();

    // Libera memoria alocada para os parametros
    delete params;

    // Finaliza task
    vTaskDelete(NULL);
}


// Func principal responsável por desserializar payload e tratar comando
bool CommandProcessor::manage(const std::string& payload) {
    
    // Extrair o subtópico (commands/macaddress/ota -> ota)
    // size_t lastSlashPos = topic.rfind('/');
    // if (lastSlashPos == std::string::npos) {
    //     ESP_LOGE(TAG, "Topic format invalid: %s", topic.c_str());
    //     return false;
    // }
    
    // Pega a substring logo após a última '/'
    // std::string subtopic = topic.substr(lastSlashPos + 1); 
    // ESP_LOGI(TAG, "Processing command: [%s]", subtopic.c_str());

    // Parse do JSON
    ESP_LOGI(TAG, "RAW PAYLOAD: %s", payload.c_str());

    cJSON* root = cJSON_Parse(payload.c_str());
    if (root == NULL) {
        ESP_LOGE(TAG, "The payload %s is not a valid JSON.", payload.c_str());
        std::string msgOut = "The payload " + payload + " is not a valid JSON.";
        AppState::setError(
            ErrorCode::COMMAND_RESPONSE_INVALID, 
            msgOut, 
            {TAG, "manage"}
        );
        return false;
    }  

    cJSON* command = cJSON_GetObjectItem(root, "command");
    if (!cJSON_IsString(command)) {
        ESP_LOGE(TAG, "Command is not string value");
        std::string msgOut = "Cannot read command (not String)";
        AppState::setError(
            ErrorCode::PROVISIONING_RESPONSE_INVALID,
            msgOut,
            {TAG, "manage"}
        );
        cJSON_Delete(root);
        return false;
    }

    std::string finalCommand = command->valuestring;

    cJSON* payloadJson = cJSON_GetObjectItem(root, "payload");
    if (!cJSON_IsObject(payloadJson)) {
        ESP_LOGE(TAG, "Payload is not string value");
        std::string msgOut = "Payload is missing or is not an object.";
        AppState::setError(
            ErrorCode::PROVISIONING_RESPONSE_INVALID,
            msgOut,
            {TAG, "manage"}
        );
        cJSON_Delete(root);
        return false;
    }

    // std::string payloadFinal = payloadJson->valuestring;

    bool success = false;

    switch (getCommandType(finalCommand)) {
        
        // =========== OTA COMMAND ==========
        case CommandType::UPDATE: {

            cJSON* newVersion = cJSON_GetObjectItem(payloadJson, "version");
            cJSON* url = cJSON_GetObjectItem(payloadJson, "url");

           if (newVersion == NULL) {
                ESP_LOGE(TAG, "Key 'version' not found in payload.");
                AppState::setError(ErrorCode::COMMAND_RESPONSE_INVALID, "Missing 'version'", {TAG, "manage"});
                break;
            }
            if (url == NULL) {
                ESP_LOGE(TAG, "Key 'url' not found in payload.");
                AppState::setError(ErrorCode::COMMAND_RESPONSE_INVALID, "Missing 'url'", {TAG, "manage"});
                break;
            }

            // Now check the types
            if (!cJSON_IsNumber(newVersion)) {
                ESP_LOGE(TAG, "'version' is not a number.");
                AppState::setError(ErrorCode::COMMAND_RESPONSE_INVALID, "Version is not a number", {TAG, "manage"});
                break;
            }
            if (!cJSON_IsString(url)) {
                ESP_LOGE(TAG, "'url' is not a string.");
                AppState::setError(ErrorCode::COMMAND_RESPONSE_INVALID, "Url is not a string", {TAG, "manage"});
                break;
            }

            if (AppState::is(DeviceState::OTA_FOUND) || AppState::is(DeviceState::OTA_DOWNLOADING)) {
                ESP_LOGW(TAG, "OTA already in progress, ignoring duplicate command.");
                success = true;
                break;
            }

            ESP_LOGI(TAG, "OTA COMMAND RECEIVED v%.2f", newVersion->valuedouble);
            AppState::transition(DeviceState::OTA_FOUND, {TAG, "manage"});

            OtaTaskParams* params = new OtaTaskParams();
            params->newVersion = newVersion->valuedouble;
            params->url_bin = url->valuestring;

            BaseType_t xReturned = xTaskCreate(
                ota_task_routine,
                "ota_update_task",
                8192,
                (void*)params,
                5,
                NULL
            );

            if (xReturned == pdPASS) {
                ESP_LOGI(TAG, "Task de OTA criada com sucesso.");
                success = true; 
            } else {
                ESP_LOGE(TAG, "Falha ao criar Task de OTA. Memoria insuficiente?");
                AppState::setError(ErrorCode::OTA_FAIL, "Failed to create OTA Task", {TAG, "manage"});
                delete params; // Limpa se falhou
                success = false;
            }

            break; 
        }

        // ========  SLEEP COMMAND ===========
        case CommandType::SLEEP: {
            cJSON* duration = cJSON_GetObjectItem(root, "duration_s");

            if (!cJSON_IsNumber(duration) || duration == NULL) {
                ESP_LOGE(TAG, "Json content invalid. Duration is not a num or its NULL.");
                std::string msgOut = "Json content invalid. Duration is not a num or its NULL.";
                AppState::setError(
                    ErrorCode::COMMAND_RESPONSE_INVALID, 
                    msgOut, 
                    {TAG, "manage"}
                );
                break;
            }

            ESP_LOGI(TAG, "Sleeping command received: %d s", duration->valueint);
            success = true;
            break;
        }

        // ========  REBOOT COMMAND ===========
        case CommandType::REBOOT: {
            ESP_LOGI(TAG, "Comando REBOOT recebido. Reiniciando...");
            AppState::transition(DeviceState::REBOOTING, {TAG, "manage"});
            success = true;
            break;
        }

        // ========  UNKNOWN COMMAND ===========
        case CommandType::UNKNOWN:
        default: {
            ESP_LOGW(TAG, "Comando ignorado ou desconhecido: %s", finalCommand.c_str());
            break;
        }
    }

    cJSON_Delete(root);
    return success;
}

void CommandProcessor::execute() {
    // ...
}