#include "CryptoManager.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "cJSON.h"
#include "freertos/FreeRTOS.h"
#include "AppState.h"



// Bibliotecas da mbedTLS
#include "mbedtls/entropy.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/bignum.h"
#include "mbedtls/pk.h"
#include "mbedtls/x509_csr.h"
#include <string.h> // Para memset

static const char* TAG = "CryptoManager";
static const char* NVS_NAMESPACE = "crypto_store";

static std::string escapeJSONString(const std::string& input) {
    std::string output;
    for (char c : input) {
        if (c == '\n') output += "\\n";
        else if (c == '\r') output += "\\r";
        else if (c == '\"') output += "\\\"";
        else output += c;
    }

    return output;
}

// ==========================================
// MÉTODOS PÚBLICOS

bool CryptoManager::isProvisioned() {
    std::string cert = loadFromNVS("client_cert");
    return !cert.empty();
}


std::string CryptoManager::handleProvisioning(const std::string& identifierCN, const std::string provisioningToken) {
    // Gera as chaves e o CSR
    std::string csr = CryptoManager::generateCSR(identifierCN);
            
    if (!csr.empty()) {
        return 
            "{\"provisioningToken\":\"" + provisioningToken +
            "\",\"macAddress\":\"" + identifierCN +
            "\",\"publicKey\":\"" + escapeJSONString(csr) + "\"}";
    }

    return "";
}

bool CryptoManager::handleProvisioningResponse(const std::string& responseBuffer, std::string& msgOut) {
    cJSON *root = cJSON_Parse(responseBuffer.c_str());

    if (root == NULL) {
        ESP_LOGE(TAG, "Fatal error, API provisioning response is not a valid JSON");
        std::string msgOut = "API provisioning response is not a valid JSON.";
        AppState::setError(
            ErrorCode::PROVISIONING_RESPONSE_INVALID,
            msgOut,
            {TAG, "saveToNVS"}
        );
        return false;
    }

    auto cleanup = [&]() {
        if (root) cJSON_Delete(root);
    };

    cJSON* successNode = cJSON_GetObjectItem(root, "success");
    if (!cJSON_IsTrue(successNode)) {
        ESP_LOGE(TAG, "O servidor recusou a ativacao (success: false).");
        std::string msgOut = "API return is not success.";
        AppState::setError(
            ErrorCode::PROVISIONING_RESPONSE_INVALID,
            msgOut,
            {TAG, "saveToNVS"}
        );
        cleanup();
        return false;
    }

    cJSON* dataNode = cJSON_GetObjectItem(root, "data");
    if (!cJSON_IsString(dataNode)) {
        ESP_LOGE(TAG, "Certificado nao encontrado no campo 'data'.");
        std::string msgOut = "Certified not found on field 'data'";
        AppState::setError(
            ErrorCode::PROVISIONING_RESPONSE_INVALID,
            msgOut,
            {TAG, "saveToNVS"}
        );
        cleanup();
        return false;
    }
    
    std::string finalCert = dataNode->valuestring;
    if (!CryptoManager::saveCertificate(finalCert)) {
        ESP_LOGE(TAG, "Error while saving the certificate on NVS");
        cleanup();
        return false;
    }
    
    nvs_handle_t handle;
    if (nvs_open("crypto_store", NVS_READWRITE, &handle) == ESP_OK) {
        nvs_erase_key(handle, "prov_token");
        nvs_commit(handle);
        nvs_close(handle);
        ESP_LOGI(TAG, "Token de provisionamento apagado da NVS com sucesso.");
    }

    ESP_LOGI(TAG, "Certificate successfully saved, rebooting to ensure changes...");
    cleanup();

    vTaskDelay(pdMS_TO_TICKS(2000));
    esp_restart();
}

// para dev
/* std::string CryptoManager::handleRegisteringResponse(const std::string& responseBuffer, std::string& msgOut) {
    cJSON* root = cJSON_Parse(responseBuffer.c_str());

    if (root == NULL) {
        ESP_LOGE(TAG, "Fatal error, API registering response is not a valid JSON");
        return "";
    }

    cJSON* tokenNode = cJSON_GetObjectItem(root, "token");

    if (!cJSON_IsString(tokenNode)) {
        ESP_LOGE(TAG, "JSON has no Token Attatched.");
        cJSON_Delete(root);
        return "";
    }

    std::string finalToken = tokenNode->valuestring;
    ESP_LOGI(TAG, "TOKEN EXTRAIDO DO JSON: %s", finalToken.c_str());

    cJSON_Delete(root);
    return finalToken;
    
} */

std::string CryptoManager::getPrivateKey() {
    return loadFromNVS("private_key");
}

std::string CryptoManager::getCertificate() {
    return loadFromNVS("client_cert");
}

// ==========================================
// MÉTODOS PRIVADOS 

bool CryptoManager::saveToNVS(const char* key, const std::string& data) {
    nvs_handle_t handle;

    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        std::string msgOut = "NVS open failed [key=" + std::string(key) + "]";
        AppState::setError(
            ErrorCode::NVS_INIT_FAIL,
            msgOut,
            {TAG, "saveToNVS"}
        );
        return false;
    }

    err = nvs_set_str(handle, key, data.c_str());
    if (err != ESP_OK) {
        std::string msgOut = "NVS set failed [key=" + std::string(key) + "]";
        AppState::setError(
            ErrorCode::NVS_WRITE_FAIL,
            msgOut,
            {TAG, "saveToNVS"}
        );
        nvs_close(handle);
        return false;
    }

    err = nvs_commit(handle);
    if (err != ESP_OK) {
        std::string msgOut = "NVS commit failed [key=" + std::string(key) + "]";
        AppState::setError(
            ErrorCode::NVS_COMMIT_FAIL,
            msgOut,
            {TAG, "saveToNVS"}
        );
        nvs_close(handle);
        return false;
    }

    nvs_close(handle);
    return true;
}

std::string CryptoManager::loadFromNVS(const char* key) {
    nvs_handle_t handle;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle);
    if (err != ESP_OK) return "";

    size_t required_size = 0;
    err = nvs_get_str(handle, key, NULL, &required_size);
    if (err != ESP_OK || required_size == 0) {
        nvs_close(handle);
        std::string msgOut = "NVS failed to load [key: " + std::string(key) + "] from NVS";
        AppState::setError(
            ErrorCode::NVS_LOAD_FAIL, 
            msgOut, 
            {TAG, "loadFromNVS"}
        );
        return "";
    }

    char* buffer = new char[required_size];
    nvs_get_str(handle, key, buffer, &required_size);
    std::string result(buffer);
    
    delete[] buffer;
    nvs_close(handle);
    
    return result;
}

bool CryptoManager::saveCertificate(const std::string& certPem) {
    return saveToNVS("client_cert", certPem);
}

std::string CryptoManager::generateCSR(const std::string& macAddress) {
    ESP_LOGI(TAG, "Iniciando geração de Chaves e CSR via mbedTLS...");

    mbedtls_pk_context key;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context ctr_drbg;
    mbedtls_x509write_csr req;
    
    const char *pers = "esp32_iot_gen";
    char subject_name[128];
    std::string csrString = "";
    int ret;

    // Inicializa as estruturas
    mbedtls_pk_init(&key);
    mbedtls_ctr_drbg_init(&ctr_drbg);
    mbedtls_entropy_init(&entropy);
    mbedtls_x509write_csr_init(&req);

    // Alocação dinâmica (Heap) para evitar Stack Overflow
    unsigned char* priv_pem = new unsigned char[1024];
    unsigned char* csr_pem = new unsigned char[4096];
    
    if (!priv_pem || !csr_pem) {
        ESP_LOGE(TAG, "Falha ao alocar memória no Heap para os buffers PEM!");
        std::string msgOut = "Failed to alocate memory for PEM buffers.";
        AppState::setError(
            ErrorCode::MEMORY_ALOCATION_FAIL, 
            msgOut, 
            {TAG, "generateCSR"}
        );
        goto cleanup; 
    }

    memset(priv_pem, 0, 1024);
    memset(csr_pem, 0, 4096);

    // 2 Prepara o Gerador de Números Aleatórios 
    mbedtls_ctr_drbg_seed(&ctr_drbg, mbedtls_entropy_func, &entropy, (const unsigned char *)pers, strlen(pers));

    // 3 Configura a Chave para ECC (Curva Elíptica secp256r1)
    ESP_LOGI(TAG, "Gerando par de chaves ECC...");
    mbedtls_pk_setup(&key, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    ret = mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(key), mbedtls_ctr_drbg_random, &ctr_drbg);
    
    if (ret != 0) {
        ESP_LOGE(TAG, "Erro ao gerar chave: -0x%04x", -ret);
        std::string msgOut = "Error when generating KEYS.";
        AppState::setError(
            ErrorCode::KEY_GENERATION_FAIL, 
            msgOut, 
            {TAG, "generateCSR"}
        );
        goto cleanup;
    }

    // 4 Escreve a Chave Privada no buffer e salva na NVS
    mbedtls_pk_write_key_pem(&key, priv_pem, 1024);
    saveToNVS("private_key", std::string((char*)priv_pem));
    ESP_LOGI(TAG, "Chave Privada gerada e salva na NVS com sucesso.");

    // 5 Configura e Gera o CSR
    ESP_LOGI(TAG, "Montando o CSR...");
    mbedtls_x509write_csr_set_md_alg(&req, MBEDTLS_MD_SHA256);
    
    // O Common Name (CN) DEVE ser o MAC Address (POR AGORA)
    snprintf(subject_name, sizeof(subject_name), "CN=%s,O=Mackenzie IoT Devices", macAddress.c_str());
    
    if (mbedtls_x509write_csr_set_subject_name(&req, subject_name) != 0) {
        ESP_LOGE(TAG, "Erro ao definir o Subject Name do CSR");
        std::string msgOut = "Error defining Subject Name on CSR.";
        AppState::setError(
            ErrorCode::CSR_SUBJECT_NAME_FAIL, 
            msgOut, 
            {TAG, "generateCSR"}
        );
        goto cleanup;
    }

    mbedtls_x509write_csr_set_key(&req, &key);

    ret = mbedtls_x509write_csr_pem(&req, csr_pem, 4096, mbedtls_ctr_drbg_random, &ctr_drbg);
    
    if (ret < 0) {
        ESP_LOGE(TAG, "Erro ao escrever CSR em PEM: -0x%04x", -ret);
        std::string msgOut = "Error when writing CSR in PEM";
        AppState::setError(
            ErrorCode::CSR_TO_PEM_FAIL, 
            msgOut, 
            {TAG, "generateCSR"}
        );
        goto cleanup;
    }

    csrString = std::string((char*)csr_pem);
    ESP_LOGI(TAG, "CSR gerado com sucesso!");

cleanup:
    // Limpa a memória RAM 
    mbedtls_x509write_csr_free(&req);
    mbedtls_pk_free(&key);
    mbedtls_ctr_drbg_free(&ctr_drbg);
    mbedtls_entropy_free(&entropy);
    
    // Deleta os arrays alocados com 'new'
    if (priv_pem) delete[] priv_pem;
    if (csr_pem) delete[] csr_pem;

    return csrString;
}
