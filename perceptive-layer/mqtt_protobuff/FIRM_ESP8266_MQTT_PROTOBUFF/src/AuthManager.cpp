#include "AuthManager.h"
#include "HttpService.h"
#include "../config.h"
#include <LittleFS.h>

static const char* TAG             = "[AuthManager]";
static const char* CREDS_FILE      = "/creds.txt";     // client_id, client_secret, device_id
static const char* WIFI_FILE       = "/wifi.txt";      // ssid, password
static const char* PROV_FLAG_FILE  = "/prov_pending";  // existe = PROVISIONING_SUCCESS nao publicado
static const char* PROV_FILE       = "/prov.txt";      // gerado pelo govApi (flash package); apagado apos provisionar

static String   s_jwt;
static uint32_t s_jwt_obtained_ms = 0;
static long     s_jwt_ttl_s       = 0;   // 0 = cache invalido

bool AuthManager::begin() {
    if (LittleFS.begin()) return true;
    // Flash vazia ou LittleFS com parametros diferentes
    Serial.printf("%s LittleFS.begin falhou (flash vazia ou imagem incompativel), formatando...\n", TAG);
    return LittleFS.format() && LittleFS.begin();
}

bool AuthManager::isProvisioned() {
    String id, secret;
    return loadCredentials(id, secret);
}

bool AuthManager::loadCredentials(String& clientId, String& clientSecret) {
    File f = LittleFS.open(CREDS_FILE, "r");
    if (!f) return false;
    clientId     = f.readStringUntil('\n');
    clientSecret = f.readStringUntil('\n');
    f.close();
    clientId.trim();
    clientSecret.trim();
    return clientId.length() > 0 && clientSecret.length() > 0;
}

String AuthManager::getClientId() {
    String id, secret;
    loadCredentials(id, secret);
    return id;
}

// Linha (0-based) sem \r\n, "" se nao existir
String AuthManager::readLine(const char* path, int line) {
    File f = LittleFS.open(path, "r");
    if (!f) return "";
    String value;
    int i = 0;
    for (; i <= line && f.available(); i++) value = f.readStringUntil('\n');
    f.close();
    if (i <= line) return "";
    value.trim();
    return value;
}

bool AuthManager::hasProvisioningFile() {
    return LittleFS.exists(PROV_FILE);
}

// chave=valor, divide no primeiro '=' (senha pode ter '=')
String AuthManager::getProvValue(const char* key) {
    File f = LittleFS.open(PROV_FILE, "r");
    if (!f) return "";
    String value;
    while (f.available()) {
        String line = f.readStringUntil('\n');
        if (line.endsWith("\r")) line.remove(line.length() - 1);
        int eq = line.indexOf('=');
        if (eq <= 0) continue;
        String k = line.substring(0, eq);
        k.trim();
        if (k == key) {
            value = line.substring(eq + 1);
            break;
        }
    }
    f.close();
    return value;
}

String AuthManager::getDeviceId() {
    String id = readLine(CREDS_FILE, 2);
    return id.length() > 0 ? id : getProvValue("device_id");
}

String AuthManager::getWifiSsid() {
    if (LittleFS.exists(WIFI_FILE)) return readLine(WIFI_FILE, 0);
    return getProvValue("wifi_ssid");
}

String AuthManager::getWifiPassword() {
    if (LittleFS.exists(WIFI_FILE)) return readLine(WIFI_FILE, 1);
    return getProvValue("wifi_pass");
}

bool AuthManager::provision(const String& macAddress, String& errOut) {
    String token    = getProvValue("prov_token");
    String deviceId = getProvValue("device_id");
    if (token.length() == 0 || deviceId.length() == 0) {
        errOut = "/prov.txt sem prov_token ou device_id";
        return false;
    }

    String url  = String(GOV_MDM_BASE_URL) + "/provisioning/activate";
    String body = String("{\"provisioningToken\":\"") + token +
                  "\",\"deviceId\":\"" + deviceId +
                  "\",\"macAddress\":\"" + macAddress + "\"}";

    Serial.printf("%s POST %s\n", TAG, url.c_str());
    String response;
    if (!HttpService::post(url, body, response, errOut)) return false;

    // {"success":true,"data":{"clientId":"...","clientSecret":"..."}}
    if (response.indexOf("\"success\":true") < 0) {
        errOut = "Provisioning response: success != true";
        return false;
    }
    String clientId, clientSecret;
    if (!jsonGetString(response, "clientId", clientId) ||
        !jsonGetString(response, "clientSecret", clientSecret)) {
        errOut = "Provisioning response: missing clientId or clientSecret";
        return false;
    }

    File f = LittleFS.open(CREDS_FILE, "w");
    if (!f) { errOut = "LittleFS: falha ao gravar credenciais"; return false; }
    f.print(clientId + "\n" + clientSecret + "\n" + deviceId + "\n");
    f.close();

    File w = LittleFS.open(WIFI_FILE, "w");
    if (!w) { errOut = "LittleFS: falha ao gravar wifi"; return false; }
    w.print(getProvValue("wifi_ssid") + "\n" + getProvValue("wifi_pass") + "\n");
    w.close();

    File flag = LittleFS.open(PROV_FLAG_FILE, "w");
    if (flag) flag.close();

    // token e de uso unico
    LittleFS.remove(PROV_FILE);

    Serial.printf("%s Credenciais Keycloak salvas (client_id=%s)\n", TAG, clientId.c_str());
    return true;
}

bool AuthManager::isProvisionAnnouncePending() {
    return LittleFS.exists(PROV_FLAG_FILE);
}

void AuthManager::clearProvisionAnnounce() {
    LittleFS.remove(PROV_FLAG_FILE);
}

void AuthManager::invalidateCache() {
    s_jwt     = "";
    s_jwt_ttl_s = 0;
    Serial.printf("%s JWT cache invalidated\n", TAG);
}

// millis(): nao depende do NTP
int AuthManager::getJwtRemainingSeconds() {
    if (s_jwt_ttl_s == 0) return 0;
    long elapsed   = (long)((millis() - s_jwt_obtained_ms) / 1000UL);
    long remaining = s_jwt_ttl_s - elapsed;
    return remaining > 0 ? (int)remaining : 0;
}

String AuthManager::getJwt() {
    if (s_jwt.length() > 0 && getJwtRemainingSeconds() > GOV_KC_TOKEN_MARGIN_S) {
        return s_jwt;
    }

    String jwt, err;
    long expiresIn = 0;
    if (!fetchJwt(jwt, expiresIn, err)) {
        Serial.printf("%s Falha ao obter JWT: %s\n", TAG, err.c_str());
        return "";
    }
    s_jwt             = jwt;
    s_jwt_obtained_ms = millis();
    s_jwt_ttl_s       = expiresIn;
    Serial.printf("%s JWT renovado (%u chars, expires_in=%lds)\n", TAG, s_jwt.length(), expiresIn);
    return s_jwt;
}

bool AuthManager::fetchJwt(String& jwtOut, long& expiresInOut, String& errOut) {
    String clientId, secret;
    if (!loadCredentials(clientId, secret)) {
        errOut = "client_id or client_secret missing in LittleFS";
        return false;
    }

    String url  = String(GOV_KEYCLOAK_URL) + "/realms/" + GOV_KEYCLOAK_REALM +
                  "/protocol/openid-connect/token";
    String body = "grant_type=client_credentials&client_id=" + clientId +
                  "&client_secret=" + secret;

    String response;
    if (!HttpService::post(url, body, response, errOut, "application/x-www-form-urlencoded")) {
        return false;
    }
    if (!jsonGetString(response, "access_token", jwtOut) ||
        !jsonGetLong(response, "expires_in", expiresInOut)) {
        errOut = "Keycloak /token response missing access_token or expires_in";
        return false;
    }
    return true;
}
