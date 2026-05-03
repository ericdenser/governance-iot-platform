#include "esp_log.h"
#include "CommandProcessor.h"
#include <string>
#include "cJSON.h"
#include "AppState.h"
#include <unordered_map>

static const char* TAG = "CommandProcessor";


// Função auxiliar para converter a String no Enum
static CommandType getCommandType(const std::string& cmd) {
    static const std::unordered_map<std::string, CommandType> commandMap = {
        {"ota",    CommandType::OTA},
        {"sleep",  CommandType::SLEEP},
        {"reboot", CommandType::REBOOT}
    };

    auto it = commandMap.find(cmd);
    if (it != commandMap.end()) {
        return it->second;
    }
    return CommandType::UNKNOWN;
}



bool CommandProcessor::manage(const std::string& topic, const std::string& payload) {
    
    // Extrair o subtópico (commands/AA:BB/ota -> ota)
    size_t lastSlashPos = topic.rfind('/');
    if (lastSlashPos == std::string::npos) {
        ESP_LOGE(TAG, "Topic format invalid: %s", topic.c_str());
        return false;
    }
    
    // Pega a substring logo após a última '/'
    std::string subtopic = topic.substr(lastSlashPos + 1); 
    ESP_LOGI(TAG, "Processing command: [%s]", subtopic.c_str());

    // Parse do JSON
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

    bool success = false;

    switch (getCommandType(subtopic)) {
        
        // =========== OTA COMMAND ==========
        case CommandType::OTA: {

            cJSON* version = cJSON_GetObjectItem(root, "version");
            cJSON* url = cJSON_GetObjectItem(root, "url");

            if (!cJSON_IsNumber(version) && !cJSON_IsString(url)) {
                ESP_LOGE(TAG, "Json content invalid. Version is a num or Url is not string.");
                std::string msgOut = "Json content invalid. Version is a num or Url is not string.";
                AppState::setError(
                    ErrorCode::COMMAND_RESPONSE_INVALID, 
                    msgOut, 
                    {TAG, "manage"}
                );
                break;
            }

            ESP_LOGI(TAG, "OTA COMMAND RECEIVED v%d", version->valueint);
            AppState::transition(DeviceState::OTA_FOUND, {TAG, "manage"});

            success = true;


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
            ESP_LOGW(TAG, "Comando ignorado ou desconhecido: %s", subtopic.c_str());
            break;
        }
    }

    cJSON_Delete(root);
    return success;
}

void CommandProcessor::execute() {
    // ...
}