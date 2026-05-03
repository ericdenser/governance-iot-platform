#ifndef CRYPTOMANAGER_H
#define CRYPTOMANAGER_H

#include <string>

class CryptoManager {
public:

    // Verifica se ja temos o certificado assinado salvo na NVS
    static bool isProvisioned();

    static bool handleRegistering();

    // Vai limpar o JSON com a resposta da API contendo o certificado
    static std::string handleProvisioning(const std::string& responseBuffer, const std::string provisioningToken);

    static std::string handleRegisteringResponse(const std::string& responseBuffer, std::string& msgOut);

    static bool handleProvisioningResponse(const std::string& responseBuffer, std::string& msgOut);

    // Recupera a chave privada da NVS
    static std::string getPrivateKey();

    // Recupera o certificado da NVS
    static std::string getCertificate();


private:
      // Salva o certificado final da CA na NVS
    static bool saveCertificate(const std::string& certPem);

    static bool saveToNVS(const char* key, const std::string& data);

    static std::string loadFromNVS(const char* key);

    // Gera o part de chaves, salva a privada na NVS e retorna o CSR
    static std::string generateCSR(const std::string& macAddress);

};

#endif