#include "OtaManager.h"
#include "HttpService.h"
#include "esp_log.h"
#include "esp_http_client.h"
#include "esp_https_ota.h"
#include "esp_task_wdt.h"
#include "cJSON.h"
#include "esp_ota_ops.h"
#include "esp_crt_bundle.h"
#include "AppState.h"

static const char *TAG = "OtaManager";
static float current_version = 0;


static bool nvs_read_float(const char* ns, const char* key, float* result) {
    nvs_handle_t h;
    if (nvs_open(ns, NVS_READONLY, &h) != ESP_OK) return false;
    uint32_t aux;
    esp_err_t err = nvs_get_u32(h, key, &aux);
    if (err == ESP_OK) {
        memcpy(result, &aux, sizeof(aux));
    }
    nvs_close(h);
    return (err == ESP_OK);
}


bool OtaManager::verify_and_update(float newVersion, std::string &url_bin, std::string &msgOut, std::function<void()> onProgressCallback) {
    
    nvs_read_float("main_store", "fw_version", &current_version);

    // Verifica se conseguiu buscar a versão na NVS
    // if (current_version == 0) {
    //     ESP_LOGE(TAG, "Unable to find firmware version in nvs.");
    //     msgOut = "Unable to find firmware version in nvs";
    //     return false;
    // }

    if (current_version == newVersion) {
        ESP_LOGE(TAG, "Stopping OTA download due same firwmare version.");
        msgOut = "Stopping OTA download due same firwmare version";
        AppState::setError(
           ErrorCode::OTA_FAIL, 
            msgOut, 
            {TAG, "verify_and_update"},
            {
                {"attempted_version", std::to_string(newVersion)},
                {"current_version", std::to_string(current_version)},
                {"firmware_url", url_bin}
            }
        );
        return false;
    }


    // ============= VERIFICACÃO DE VERSÕES INVÁLIDAS ================

    nvs_handle_t nvsHandle;
    if (nvs_open("ota_store", NVS_READWRITE, &nvsHandle) != ESP_OK) {
        msgOut = "Falha ao abrir NVS ota_store";
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        return false;
    }

    uint32_t newVersionUint = (uint32_t)newVersion;

    // Verificar versão inválida no NVS
    uint32_t invalid_ver = 0;
    esp_err_t err = nvs_get_u32(nvsHandle, "invalid_ver", &invalid_ver);
    bool doesExist = (err == ESP_OK);

    ESP_LOGI(TAG, "Versao Nova: %u | Versao atual: %u | Versao invalida: %u", newVersionUint, (uint32_t)current_version, invalid_ver);

    if (doesExist && newVersionUint == invalid_ver) {
        msgOut = "Versao " + std::to_string(newVersionUint) + " marcada como invalida. Update abortado.";
        ESP_LOGI(TAG, "%s", msgOut.c_str());
        nvs_close(nvsHandle);
        return false;
    }

    // ==========================================================


    // Se chegou aqui, versão disponível é válida
    if (newVersionUint > 0) {
        msgOut = "Atualizacao encontrada! Baixando v" + std::to_string(newVersionUint);

        // Salva versao atual para restaurar em caso de rollback
        uint32_t currentVersionUint = (uint32_t)current_version;
        nvs_set_u32(nvsHandle, "prev_ver", currentVersionUint);
        nvs_commit(nvsHandle);

        // Registra qual versão esta sendo baixada para tratamento futuro
        nvs_set_u32(nvsHandle, "target_ver", newVersionUint);
        nvs_commit(nvsHandle);

        ESP_LOGI(TAG, "%s", msgOut.c_str());

        bool result = download_OTA(url_bin, nvsHandle, newVersionUint, msgOut, onProgressCallback);
        nvs_close(nvsHandle);
        return result;

    } else {
        msgOut = "Firmware enviado nao possui versao (v0).";
        ESP_LOGI(TAG, "%s", msgOut.c_str());
        nvs_close(nvsHandle);
        return true;
    }
}

void OtaManager::set_valid_version() {
    const esp_partition_t *running = esp_ota_get_running_partition();
    esp_ota_img_states_t ota_state;

    if (esp_ota_get_state_partition(running, &ota_state) == ESP_OK) {
        ESP_LOGI(TAG, "IMAGE = %d", ota_state);
        if (ota_state == ESP_OTA_IMG_PENDING_VERIFY) {
            ESP_LOGI(TAG, "Primeiro boot apos OTA. Validando firmware...");
            if (esp_ota_mark_app_valid_cancel_rollback() == ESP_OK) {
                ESP_LOGI(TAG, "Firmware marcado como VALIDO. Rollback cancelado.");

                // OTA bem sucedido — limpa target_ver para nao disparar verify_rollback no proximo boot
                nvs_handle_t nvsHandle;
                if (nvs_open("ota_store", NVS_READWRITE, &nvsHandle) == ESP_OK) {
                    nvs_erase_key(nvsHandle, "target_ver");
                    nvs_commit(nvsHandle);
                    nvs_close(nvsHandle);
                }

            } else {
                ESP_LOGE(TAG, "Falha ao marcar firmware como valido!");
            }
        } else {
            ESP_LOGW(TAG, "Firmware ja estava validado ou nao requer validacao.");
        }
    }
}

void OtaManager::set_invalid_version(std::string& reason) {
    nvs_handle_t nvsHandle;
    if (nvs_open("ota_store", NVS_READWRITE, &nvsHandle) == ESP_OK) {

        nvs_read_float("main_store", "fw_version", &current_version);

        ESP_LOGE(TAG, "ROLLBACK ATIVADO, REASON: [%s].", reason.c_str());

        uint32_t currentVersionInt = (uint32_t)current_version;

        // registra qual a versão invalida
        nvs_set_u32(nvsHandle, "invalid_ver", currentVersionInt);

        // registra o motivo do rollback
        nvs_set_str(nvsHandle, "rollbackReason", reason.c_str());
        
        // target_ver sinaliza para verify_rollback() no reboot do firmware anterior que há rollback a reportar
        nvs_set_u32(nvsHandle, "target_ver", currentVersionInt);

        nvs_commit(nvsHandle);
        nvs_close(nvsHandle);

        ESP_LOGW(TAG, "Versao [v%.2f] banida no NVS. Iniciando rollback...", current_version);
        esp_ota_mark_app_invalid_rollback_and_reboot();
    }
}

bool OtaManager::verify_rollback(std::string& msgOut, uint32_t& outInvalidVer) {
    outInvalidVer = 0;

    const esp_partition_t *invalid_partition = esp_ota_get_last_invalid_partition();
    if (invalid_partition == NULL) {
        return false;
    }

    nvs_handle_t nvsHandle;
    if (nvs_open("ota_store", NVS_READWRITE, &nvsHandle) != ESP_OK) {
        msgOut = "Rollback detectado mas falha ao abrir ota_store";
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        return true;
    }

    uint32_t target_ver = 0;
    nvs_get_u32(nvsHandle, "target_ver", &target_ver);

    if (target_ver == 0) {
        // Se invalid_ver já está gravado, o rollback já foi processado neste boot cycle
        uint32_t invalid_ver = 0;
        if (nvs_get_u32(nvsHandle, "invalid_ver", &invalid_ver) == ESP_OK && invalid_ver > 0) {
            nvs_close(nvsHandle);
            ESP_LOGI(TAG, "Rollback ja foi processado neste boot");
            return false; // já tratado, não republica
        }

        // Rollback sem versão registrada
        nvs_close(nvsHandle);
        msgOut = "Rollback na particao '" + std::string(invalid_partition->label) + "' — versao alvo desconhecida";
        ESP_LOGW(TAG, "%s", msgOut.c_str());
        return true;
    }
    AppState::transition(DeviceState::FIRMWARE_ROLLBACK, {TAG, "verify_rollback"});

    ESP_LOGE(TAG, "ROLLBACK: particao='%s' versao_falhou=%u", invalid_partition->label, target_ver);

    nvs_set_u32(nvsHandle, "invalid_ver", target_ver);  // bane a versao
    nvs_erase_key(nvsHandle, "target_ver");             // limpa para nao redetectar no proximo boot

    // Restaura fw_version na main_store para a versão anterior ao OTA falho
    uint32_t prev_ver = 0;
    if (nvs_get_u32(nvsHandle, "prev_ver", &prev_ver) == ESP_OK) {
        nvs_handle_t mainHandle;
        if (nvs_open("main_store", NVS_READWRITE, &mainHandle) == ESP_OK) {
            float prevFloat = (float)prev_ver;
            uint32_t bits;
            memcpy(&bits, &prevFloat, sizeof(bits));
            nvs_set_u32(mainHandle, "fw_version", bits);
            nvs_commit(mainHandle);
            nvs_close(mainHandle);
            ESP_LOGI(TAG, "fw_version restaurada para v%u apos rollback.", prev_ver);
        }
    }

    nvs_commit(nvsHandle);
    nvs_close(nvsHandle);

    outInvalidVer = target_ver;
    msgOut = "Versao " + std::to_string(target_ver) +
             " causou rollback na particao '" + invalid_partition->label + "'. Banida.";
    return true;
}

bool OtaManager::download_OTA(std::string &urlBin, nvs_handle_t nvsHandle, uint32_t newVersionUint, std::string &msgOut, std::function<void()> onProgressCallback) {

    // Config do cliente OTA
    esp_http_client_config_t http_config {};
    http_config.url = urlBin.c_str();
    http_config.timeout_ms = 10000;

    if (urlBin.find("https") == 0) {
        // Se for HTTPS: usa SSL e anexa certificados
        http_config.crt_bundle_attach = esp_crt_bundle_attach;
        http_config.transport_type = HTTP_TRANSPORT_OVER_SSL;
    } else {
        // Se for HTTP: força transporte TCP simples e remove certificados
        http_config.crt_bundle_attach = NULL;
        http_config.transport_type = HTTP_TRANSPORT_OVER_TCP; 
    }

    esp_https_ota_config_t ota_config = {};
    ota_config.http_config = &http_config;

    // WiFi driver (prio 23) satura o CPU 0 durante o download, impedindo que o
    // IDLE0 ganhe tempo de CPU e seja alimentado pelo WDT. Suspendemos o
    // monitoramento dos IDLE tasks enquanto o download estiver em progresso.
    esp_task_wdt_config_t ota_wdt = {
        .timeout_ms     = 60000,
        .idle_core_mask = 0,
        .trigger_panic  = true
    };
    esp_task_wdt_reconfigure(&ota_wdt);

    // Inicia o OTA
    esp_https_ota_handle_t https_ota_handle = NULL;
    esp_err_t err = esp_https_ota_begin(&ota_config, &https_ota_handle);


    if (err != ESP_OK) {
        msgOut = "Falha OTA Begin: " + std::string(esp_err_to_name(err));
        ESP_LOGI(TAG, "%s", msgOut.c_str());
        esp_task_wdt_config_t normal_wdt = {
            .timeout_ms     = 15000,
            .idle_core_mask = (1 << portNUM_PROCESSORS) - 1,
            .trigger_panic  = true
        };
        esp_task_wdt_reconfigure(&normal_wdt);
        return false;
    }

    // Loop de Download
    AppState::transition(DeviceState::OTA_DOWNLOADING, {TAG, "download_OTA"});
    while (1) {
        // Baixa um pedaço
        err = esp_https_ota_perform(https_ota_handle);

        // Se for diferente, ou acabou ou deu erro
        if (err != ESP_ERR_HTTPS_OTA_IN_PROGRESS) {
            break; // Sai do loop
        }

        if (onProgressCallback) {
            onProgressCallback();
        }
    }
    // Verifica se terminou com sucesso
    if (esp_https_ota_is_complete_data_received(https_ota_handle) != true) {
        msgOut = "Error: Download incomplete";
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        esp_https_ota_abort(https_ota_handle);
        esp_task_wdt_config_t normal_wdt = {
            .timeout_ms     = 15000,
            .idle_core_mask = (1 << portNUM_PROCESSORS) - 1,
            .trigger_panic  = true
        };
        esp_task_wdt_reconfigure(&normal_wdt);
        return false;
    }

    // Finaliza
    esp_err_t ota_finish_err = esp_https_ota_finish(https_ota_handle);

    esp_task_wdt_config_t normal_wdt = {
        .timeout_ms     = 15000,
        .idle_core_mask = (1 << portNUM_PROCESSORS) - 1,
        .trigger_panic  = true
    };
    esp_task_wdt_reconfigure(&normal_wdt);

    if (ota_finish_err == ESP_OK) {

        // Zera o contador de crash antes de reiniciar
        nvs_set_i32(nvsHandle, "crash_count", 0);
        nvs_commit(nvsHandle);

        // Persiste a nova versão na main_store para que o próximo boot a leia corretamente
        nvs_handle_t mainHandle;
        if (nvs_open("main_store", NVS_READWRITE, &mainHandle) == ESP_OK) {
            float newVersionFloat = (float)newVersionUint;
            uint32_t versionBits;
            memcpy(&versionBits, &newVersionFloat, sizeof(versionBits));
            nvs_set_u32(mainHandle, "fw_version", versionBits);
            nvs_commit(mainHandle);
            nvs_close(mainHandle);
            ESP_LOGI(TAG, "Versao %u salva em main_store/firmware_version.", newVersionUint);
        } else {
            ESP_LOGW(TAG, "Nao foi possivel salvar firmware_version na main_store.");
        }

        msgOut = "Upgrade successful. Rebooting...";
        ESP_LOGI(TAG, "%s", msgOut.c_str());
        vTaskDelay(1000 / portTICK_PERIOD_MS);
        esp_restart();
        return true;

    } else {

        if (ota_finish_err == ESP_ERR_OTA_VALIDATE_FAILED) {
            msgOut = "Image validation failed, image is corrupted";
            ESP_LOGE(TAG, "%s", msgOut.c_str());
            return false;
        }

        msgOut = "OTA upgrade failed" + std::string(esp_err_to_name(ota_finish_err));
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        return false;
    }
    return false;
}