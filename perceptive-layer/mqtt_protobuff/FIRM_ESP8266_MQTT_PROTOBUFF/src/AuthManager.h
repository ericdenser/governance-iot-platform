#pragma once

#include <Arduino.h>

// Sem NVS: identidade em arquivos do LittleFS (sobrevive a OTA).
// Antes de provisionar vem do /prov.txt, depois de /creds.txt e /wifi.txt.
class AuthManager {
public:
    // Monta o LittleFS (formata na primeira vez)
    static bool begin();

    // True se ja tem client_id/client_secret salvos
    static bool isProvisioned();

    // POST /provisioning/activate. Sucesso: salva creds/WiFi e apaga /prov.txt
    static bool provision(const String& macAddress, String& errOut);

    // Status PROVISIONING_SUCCESS ainda nao publicado (sobrevive a reboot)
    static bool isProvisionAnnouncePending();
    static void clearProvisionAnnounce();

    // "" se o Keycloak falhar
    static String getJwt();

    // Forca fetch novo no proximo getJwt()
    static void invalidateCache();

    static int getJwtRemainingSeconds();

    static String getClientId();

    // /prov.txt existe (device ainda nao consumiu o token)
    static bool hasProvisioningFile();
    static String getProvValue(const char* key);

    // /wifi.txt apos provisionar, antes /prov.txt
    static String getDeviceId();
    static String getWifiSsid();
    static String getWifiPassword();

private:
    static bool loadCredentials(String& clientId, String& clientSecret);
    static String readLine(const char* path, int line);
    static bool fetchJwt(String& jwtOut, long& expiresInOut, String& errOut);
};
