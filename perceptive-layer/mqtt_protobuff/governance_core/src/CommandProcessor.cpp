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
#include "esp_sleep.h"
#include "freertos/task.h"
#include <esp_ota_ops.h>
#include "esp_random.h"

static const char* TAG = "CommandProcessor";

struct OtaTaskParams {
    std::string newVersion;
    std::string url_bin;
};

// get string -> enum
static CommandType getCommandType(std::string cmd) {

    std::transform(cmd.begin(), cmd.end(), cmd.begin(),
        [](unsigned char c){ return std::tolower(c); });

    static const std::unordered_map<std::string, CommandType> commandMap = {
        {"update",             CommandType::UPDATE},
        {"deep_sleep",         CommandType::DEEP_SLEEP},
        {"reboot",             CommandType::REBOOT},
        {"firmware_rollback",  CommandType::FIRMWARE_ROLLBACK}
    };

    auto it = commandMap.find(cmd);

    if (it != commandMap.end()) {
        return it->second;
    }

    return CommandType::UNKNOWN;
}


static void ota_task_routine(void* pvParameters) {

    OtaTaskParams* params = (OtaTaskParams*)pvParameters;

    #if CONFIG_GOV_OTA_JITTER_MAX_MS > 0
        uint32_t jitter_ms = esp_random() % CONFIG_GOV_OTA_JITTER_MAX_MS;
        ESP_LOGI(TAG, "OTA jitter: waiting %lu ms before download", (unsigned long)jitter_ms);
        vTaskDelay(pdMS_TO_TICKS(jitter_ms));
    #endif

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

    bool success = false;


    // TREAT COMMAND
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

            if (!cJSON_IsString(newVersion)) {
                ESP_LOGE(TAG, "'version' is not a string.");
                AppState::setError(ErrorCode::COMMAND_RESPONSE_INVALID, "Version is not a string", {TAG, "manage"});
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

            ESP_LOGI(TAG, "OTA COMMAND RECEIVED FOR UPGRADING TO v%s", newVersion->valuestring);
            AppState::transition(DeviceState::OTA_FOUND, {TAG, "manage"});

            OtaTaskParams* params = new OtaTaskParams();
            params->newVersion = newVersion->valuestring;
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
        case CommandType::DEEP_SLEEP: {
            cJSON* duration = cJSON_GetObjectItem(payloadJson, "duration_s");

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

            if (duration->valueint <= 0) {
                ESP_LOGE(TAG, "Duracao invalida, tempo menor igual a 0");
                std::string msgOut = "Json content invalid. Duration is less or equal than 0.";
                AppState::setError(
                    ErrorCode::COMMAND_RESPONSE_INVALID, 
                    msgOut, 
                    {TAG, "manage"}
                );
                break;
            }

            ESP_LOGI(TAG, "Sleeping command received: %d s", duration->valueint);

            // Sleep -> micro seconds
            esp_sleep_enable_timer_wakeup((uint64_t)duration->valueint * 1000000ULL); 

            nvs_handle_t nvsHandler;
            esp_err_t err = nvs_open("command_store", NVS_READWRITE, &nvsHandler);

            if (err == ESP_OK) {
                nvs_set_str(nvsHandler, "lastCommand", finalCommand.c_str()); 
                nvs_set_i8(nvsHandler, "commandNotified", 0); // notificar no reboot
                nvs_commit(nvsHandler);
                nvs_close(nvsHandler);

                esp_deep_sleep_start(); // noreturn

            } else {

                ESP_LOGE(TAG, "Falha ao inicializar NVS: %s", esp_err_to_name(err));
                AppState::setError(ErrorCode::NVS_INIT_FAIL, esp_err_to_name(err), {TAG, "manage"});

            }

            break;
        }

        // ========  REBOOT COMMAND ===========
        case CommandType::REBOOT: {
            ESP_LOGI(TAG, "Comando REBOOT recebido.");

            nvs_handle_t nvsHandler;
            esp_err_t err = nvs_open("command_store", NVS_READWRITE, &nvsHandler);

            if (err == ESP_OK) {
                nvs_set_str(nvsHandler, "lastCommand", finalCommand.c_str()); 
                nvs_set_i8(nvsHandler, "commandNotified", 0); // 0 ainda nao notificado, 1 notificado
                nvs_commit(nvsHandler);
                nvs_close(nvsHandler);

                esp_restart(); //noreturn

            } else {
                ESP_LOGE(TAG, "Falha ao inicializar NVS: %s", esp_err_to_name(err));
                AppState::setError(ErrorCode::NVS_INIT_FAIL, esp_err_to_name(err), {TAG, "manage"});
            }

            break;
        }

        
        case CommandType::FIRMWARE_ROLLBACK: {
            const esp_partition_t* prev = esp_ota_get_next_update_partition(NULL);
            if (prev == NULL) {
                ESP_LOGE(TAG, "Sem partição anterior disponível para rollback forçado.");
                AppState::setError(ErrorCode::FIRMWARE_ROLLBACK_FAILED,
                                   "Sem partição anterior disponível", {TAG, "manage"});
                break;
            }

            char prev_ver_buf[32] = {0};
            char fw_ver_buf[32]   = {0};
            nvs_handle_t otaHandle;
            if (nvs_open("ota_store", NVS_READONLY, &otaHandle) == ESP_OK) {
                size_t pv_len = sizeof(prev_ver_buf);
                nvs_get_str(otaHandle, "prev_ver", prev_ver_buf, &pv_len);
                nvs_close(otaHandle);
            }
            nvs_handle_t mainReadHandle;
            if (nvs_open("main_store", NVS_READONLY, &mainReadHandle) == ESP_OK) {
                size_t fv_len = sizeof(fw_ver_buf);
                nvs_get_str(mainReadHandle, "fw_version", fw_ver_buf, &fv_len);
                nvs_close(mainReadHandle);
            }

            std::string prev_ver   = prev_ver_buf;
            std::string fw_version = fw_ver_buf;

            if (prev_ver.empty()) {
                ESP_LOGE(TAG, "Nenhuma versão anterior registrada no NVS — rollback abortado.");
                AppState::setError(ErrorCode::FIRMWARE_ROLLBACK_FAILED,
                                   "Nenhuma versão anterior registrada", {TAG, "manage"});
                break;
            }

            if (prev_ver == fw_version) {
                ESP_LOGW(TAG, "Já na versão anterior (v%s) — rollback ignorado.", fw_version.c_str());
                break;
            }

            // Restaura fw_version e apaga prev_ver antes do reboot
            nvs_handle_t mainHandle;
            if (nvs_open("main_store", NVS_READWRITE, &mainHandle) == ESP_OK) {
                nvs_set_str(mainHandle, "fw_version", prev_ver.c_str());
                nvs_commit(mainHandle);
                nvs_close(mainHandle);
            }

            nvs_handle_t otaWriteHandle;
            if (nvs_open("ota_store", NVS_READWRITE, &otaWriteHandle) == ESP_OK) {
                nvs_erase_key(otaWriteHandle, "prev_ver");
                nvs_commit(otaWriteHandle);
                nvs_close(otaWriteHandle);
            }

            nvs_handle_t cmdHandle;
            if (nvs_open("command_store", NVS_READWRITE, &cmdHandle) == ESP_OK) {
                nvs_set_str(cmdHandle, "lastCommand", finalCommand.c_str());
                nvs_set_i8(cmdHandle, "commandNotified", 0);
                nvs_commit(cmdHandle);
                nvs_close(cmdHandle);
            }

            esp_err_t err = esp_ota_set_boot_partition(prev);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "Falha ao setar partição para rollback: %s", esp_err_to_name(err));
                AppState::setError(ErrorCode::OTA_FAIL,
                                   std::string("Falha ao setar partição: ") + esp_err_to_name(err),
                                   {TAG, "manage"});
                break;
            }

            ESP_LOGI(TAG, "Rollback forçado para v%s. Reiniciando...", prev_ver.c_str());
            vTaskDelay(pdMS_TO_TICKS(500));
            esp_restart();
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
