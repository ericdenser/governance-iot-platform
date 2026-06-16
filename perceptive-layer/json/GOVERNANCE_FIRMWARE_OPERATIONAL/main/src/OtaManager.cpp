#include "OtaManager.h"
#include "esp_log.h"
#include "esp_http_client.h"
#include "esp_https_ota.h"
#include "cJSON.h"
#include "esp_ota_ops.h"
#include "esp_crt_bundle.h"
#include "AppState.h"

static const char *TAG = "OtaManager";
static std::string current_version;

static bool nvs_read_str(const char* ns, const char* key, char* buf, size_t len) {
    nvs_handle_t h;
    if (nvs_open(ns, NVS_READONLY, &h) != ESP_OK) return false;
    esp_err_t err = nvs_get_str(h, key, buf, &len);
    nvs_close(h);
    return (err == ESP_OK);
}


bool OtaManager::verify_and_update(const std::string& newVersion, std::string &url_bin, std::string &msgOut, std::function<void()> onProgressCallback) {

    char fw_buf[32] = {0};
    nvs_read_str("main_store", "fw_version", fw_buf, sizeof(fw_buf));
    current_version = fw_buf;

    if (current_version == newVersion) {
        ESP_LOGE(TAG, "Stopping OTA download due same firmware version.");
        msgOut = "Stopping OTA download due same firmware version";
        AppState::setError(
            ErrorCode::OTA_FAIL,
            msgOut,
            {TAG, "verify_and_update"},
            {
                {"attempted_version", newVersion},
                {"current_version",   current_version},
                {"firmware_url",      url_bin}
            }
        );
        return false;
    }

    // ============= VERIFICAÇÃO DE VERSÕES INVÁLIDAS ================

    nvs_handle_t nvsHandle;
    if (nvs_open("ota_store", NVS_READWRITE, &nvsHandle) != ESP_OK) {
        msgOut = "Falha ao abrir NVS ota_store";
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        return false;
    }

    char invalid_ver_buf[32] = {0};
    size_t iv_len = sizeof(invalid_ver_buf);
    bool invalid_ver_exists = (nvs_get_str(nvsHandle, "invalid_ver", invalid_ver_buf, &iv_len) == ESP_OK)
                               && (invalid_ver_buf[0] != '\0');
    std::string invalid_ver = invalid_ver_buf;

    ESP_LOGI(TAG, "Versao Nova: %s | Versao atual: %s | Versao invalida: %s",
             newVersion.c_str(), current_version.c_str(), invalid_ver.c_str());

    if (invalid_ver_exists && newVersion == invalid_ver) {
        msgOut = "Versao " + newVersion + " marcada como invalida. Update abortado.";
        ESP_LOGI(TAG, "%s", msgOut.c_str());
        nvs_close(nvsHandle);
        return false;
    }

    // ==========================================================

    if (!newVersion.empty()) {
        msgOut = "Atualizacao encontrada! Baixando v" + newVersion;

        nvs_set_str(nvsHandle, "prev_ver",   current_version.c_str());
        nvs_set_str(nvsHandle, "target_ver", newVersion.c_str());
        nvs_commit(nvsHandle);

        ESP_LOGI(TAG, "%s", msgOut.c_str());

        bool result = download_OTA(url_bin, nvsHandle, newVersion, msgOut, onProgressCallback);
        nvs_close(nvsHandle);
        return result;

    } else {
        msgOut = "Firmware enviado nao possui versao.";
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

                nvs_handle_t nvsHandle;
                if (nvs_open("ota_store", NVS_READWRITE, &nvsHandle) == ESP_OK) {
                    nvs_erase_key(nvsHandle, "target_ver");
                    nvs_set_i8(nvsHandle, "ota_notified", 0);
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

        char fw_buf[32] = {0};
        nvs_read_str("main_store", "fw_version", fw_buf, sizeof(fw_buf));
        current_version = fw_buf;

        ESP_LOGE(TAG, "ROLLBACK ATIVADO, REASON: [%s].", reason.c_str());

        nvs_set_str(nvsHandle, "invalid_ver",    current_version.c_str());
        nvs_set_str(nvsHandle, "rollbackReason", reason.c_str());
        nvs_set_str(nvsHandle, "target_ver",     current_version.c_str());

        nvs_commit(nvsHandle);
        nvs_close(nvsHandle);

        ESP_LOGW(TAG, "Versao [%s] banida no NVS. Iniciando rollback...", current_version.c_str());
        esp_ota_mark_app_invalid_rollback_and_reboot();
    }
}

bool OtaManager::verify_rollback(std::string& msgOut, std::string& outInvalidVer) {
    outInvalidVer.clear();

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

    char target_ver_buf[32] = {0};
    size_t tv_len = sizeof(target_ver_buf);
    nvs_get_str(nvsHandle, "target_ver", target_ver_buf, &tv_len);
    std::string target_ver = target_ver_buf;

    if (target_ver.empty()) {
        char invalid_ver_buf[32] = {0};
        size_t iv_len = sizeof(invalid_ver_buf);
        if (nvs_get_str(nvsHandle, "invalid_ver", invalid_ver_buf, &iv_len) == ESP_OK
            && invalid_ver_buf[0] != '\0') {
            nvs_close(nvsHandle);
            ESP_LOGI(TAG, "Rollback ja foi processado neste boot");
            return false;
        }

        nvs_close(nvsHandle);
        msgOut = "Rollback na particao '" + std::string(invalid_partition->label) + "' — versao alvo desconhecida";
        ESP_LOGW(TAG, "%s", msgOut.c_str());
        return true;
    }

    AppState::transition(DeviceState::FIRMWARE_ROLLBACK, {TAG, "verify_rollback"});
    ESP_LOGE(TAG, "ROLLBACK: particao='%s' versao_falhou=%s", invalid_partition->label, target_ver.c_str());

    nvs_set_str(nvsHandle, "invalid_ver", target_ver.c_str());
    nvs_erase_key(nvsHandle, "target_ver");

    char prev_ver_buf[32] = {0};
    size_t pv_len = sizeof(prev_ver_buf);
    if (nvs_get_str(nvsHandle, "prev_ver", prev_ver_buf, &pv_len) == ESP_OK && prev_ver_buf[0] != '\0') {
        nvs_handle_t mainHandle;
        if (nvs_open("main_store", NVS_READWRITE, &mainHandle) == ESP_OK) {
            nvs_set_str(mainHandle, "fw_version", prev_ver_buf);
            nvs_commit(mainHandle);
            nvs_close(mainHandle);
            ESP_LOGI(TAG, "fw_version restaurada para v%s apos rollback.", prev_ver_buf);
        }
    }

    nvs_commit(nvsHandle);
    nvs_close(nvsHandle);

    outInvalidVer = target_ver;
    msgOut = "Versao " + target_ver +
             " causou rollback na particao '" + invalid_partition->label + "'. Banida.";
    return true;
}

bool OtaManager::download_OTA(std::string &urlBin, nvs_handle_t nvsHandle, const std::string& newVersion, std::string &msgOut, std::function<void()> onProgressCallback) {

    esp_http_client_config_t http_config {};
    http_config.url = urlBin.c_str();
    http_config.timeout_ms = 10000;

    if (urlBin.find("https") == 0) {
        http_config.crt_bundle_attach = esp_crt_bundle_attach;
        http_config.transport_type = HTTP_TRANSPORT_OVER_SSL;
    } else {
        http_config.crt_bundle_attach = NULL;
        http_config.transport_type = HTTP_TRANSPORT_OVER_TCP;
    }

    esp_https_ota_config_t ota_config = {};
    ota_config.http_config = &http_config;

    esp_https_ota_handle_t https_ota_handle = NULL;
    esp_err_t err = esp_https_ota_begin(&ota_config, &https_ota_handle);

    if (err != ESP_OK) {
        msgOut = "Falha OTA Begin: " + std::string(esp_err_to_name(err));
        ESP_LOGI(TAG, "%s", msgOut.c_str());
        return false;
    }

    AppState::transition(DeviceState::OTA_DOWNLOADING, {TAG, "download_OTA"});
    while (1) {
        err = esp_https_ota_perform(https_ota_handle);
        if (err != ESP_ERR_HTTPS_OTA_IN_PROGRESS) {
            break;
        }
        if (onProgressCallback) {
            onProgressCallback();
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }

    if (esp_https_ota_is_complete_data_received(https_ota_handle) != true) {
        msgOut = "Error: Download incomplete";
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        esp_https_ota_abort(https_ota_handle);
        return false;
    }

    esp_err_t ota_finish_err = esp_https_ota_finish(https_ota_handle);

    if (ota_finish_err == ESP_OK) {

        nvs_set_i32(nvsHandle, "crash_count", 0);
        nvs_commit(nvsHandle);

        nvs_handle_t mainHandle;
        if (nvs_open("main_store", NVS_READWRITE, &mainHandle) == ESP_OK) {
            nvs_set_str(mainHandle, "fw_version", newVersion.c_str());
            nvs_commit(mainHandle);
            nvs_close(mainHandle);
            ESP_LOGI(TAG, "Versao %s salva em main_store/fw_version.", newVersion.c_str());
        } else {
            ESP_LOGW(TAG, "Nao foi possivel salvar fw_version na main_store.");
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

        msgOut = "OTA upgrade failed: " + std::string(esp_err_to_name(ota_finish_err));
        ESP_LOGE(TAG, "%s", msgOut.c_str());
        return false;
    }
    return false;
}
