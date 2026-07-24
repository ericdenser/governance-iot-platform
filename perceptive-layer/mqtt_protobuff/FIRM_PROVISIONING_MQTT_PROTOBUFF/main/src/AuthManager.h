#pragma once

#include <string>

// Gerencia credenciais Keycloak e cache de JWT
class AuthManager {
    public:

        // True if device already provisioned
        static bool isProvisioned();

        // Parse response from POST /provisioning/activate
        // Success: errase prov_token, save credentials on NVS and restart
        // Fail: fills msgOut, set AppState and return false
        static bool handleProvisioningResponse(const std::string& responseBuffer, std::string& msgOut);

        // Return valid JWT for MQTT CONNECT (username)
        static std::string getJwt();

        // Invalidate cache -> forced token renovation on next getJwt()
        // Call when broker reject by expired auth or token exp
        static void invalidateCache();

        static int getJwtRemainingSeconds();

        static std::string getDeviceId();


    private:

        static bool saveCredentials(const std::string& clientId, 
                                    const std::string& clientSecret);

        static bool saveToNVS(const char* key, const std::string& data);
        static std::string loadFromNVS(const char* key);


        static bool fetchJwt(std::string& jwtOut, int& expiresInOut, std::string& errOut);


};