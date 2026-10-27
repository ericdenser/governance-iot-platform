#include "OtaManager.h"
#include "HttpService.h"
#include "AuthManager.h"
#include <ESP8266HTTPClient.h>
#include <ESP8266httpUpdate.h>
#include <LittleFS.h>

static const char* TAG = "[OtaManager]";

static const char* FW_FILE       = "/fw.txt";           // version, sha, sketch_md5
static const char* PENDING_FILE  = "/ota_pending.txt";  // target_ver, target_sha, prev_md5
static const char* ANNOUNCE_FILE = "/ota_announce";     // existe = OTA_SUCCESSFUL nao publicado
static const char* FAIL_FILE     = "/ota_fail.txt";     // msg, attempted_version, attempted_sha

static const char* UNKNOWN_VERSION = "unknown";

static String s_version;
static String s_sha;
static String s_md5;   // MD5 do sketch rodando

static bool writeLines(const char* path, const String& a, const String& b, const String& c) {
    File f = LittleFS.open(path, "w");
    if (!f) return false;
    f.print(a + "\n" + b + "\n" + c + "\n");
    f.close();
    return true;
}

static bool readLines(const char* path, String& a, String& b, String& c) {
    File f = LittleFS.open(path, "r");
    if (!f) return false;
    a = f.readStringUntil('\n'); a.trim();
    b = f.readStringUntil('\n'); b.trim();
    c = f.readStringUntil('\n'); c.trim();
    f.close();
    return true;
}

void OtaManager::bootAudit() {
    s_md5 = ESP.getSketchMD5();

    // Sketch mudou = imagem nova aplicada
    String targetVer, targetSha, prevMd5;
    if (readLines(PENDING_FILE, targetVer, targetSha, prevMd5)) {
        LittleFS.remove(PENDING_FILE);
        if (s_md5 != prevMd5) {
            writeLines(FW_FILE, targetVer, targetSha, s_md5);
            File flag = LittleFS.open(ANNOUNCE_FILE, "w");
            if (flag) flag.close();
            Serial.printf("%s OTA aplicado: v%s (MD5 %s)\n", TAG, targetVer.c_str(), s_md5.c_str());
        } else {
            savePendingFailure("Imagem nova nao foi aplicada no boot (sketch inalterado)", targetVer, targetSha);
            Serial.printf("%s OTA para v%s NAO aplicado (sketch inalterado)\n", TAG, targetVer.c_str());
        }
    }

    // /fw.txt so vale se for deste sketch
    String ver, sha, md5;
    if (readLines(FW_FILE, ver, sha, md5) && md5 == s_md5 && ver.length() > 0) {
        s_version = ver;
        s_sha     = sha;
        return;
    }

    // Primeiro boot: versao e SHA do /prov.txt
    s_sha     = AuthManager::getProvValue("fw_sha");
    s_version = AuthManager::getProvValue("fw_version");
    if (s_version.length() > 0) {
        writeLines(FW_FILE, s_version, s_sha, s_md5);
    } else {
        // gravado pela serial sem flash package: versao desconhecida
        s_version = UNKNOWN_VERSION;
    }
}

String OtaManager::currentVersion() { return s_version; }
String OtaManager::currentSha()     { return s_sha; }

// Recusa imagem que nao for ESP8266: ESP32 tambem comeca com 0xE9 e brickaria o device
// ESP8266: bytes 8..11 = endereco de carga (0x3FFxxxxx / 0x40xxxxxx). ESP32: byte 8 = 0xEE
bool OtaManager::checkImageHeader(const String& url, String& msgOut) {
    std::unique_ptr<WiFiClient> client = HttpService::makeClient(url);
    HTTPClient http;
    http.setTimeout(10000);
    http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
    if (!http.begin(*client, url)) {
        msgOut = "Falha OTA Begin: http.begin";
        return false;
    }
    http.addHeader("Range", "bytes=0-15");
    int code = http.GET();
    if (code != HTTP_CODE_OK && code != HTTP_CODE_PARTIAL_CONTENT) {
        msgOut = "Falha OTA Begin: HTTP " + String(code) + " " + HTTPClient::errorToString(code);
        http.end();
        return false;
    }

    uint8_t header[16] = {};
    size_t n = http.getStream().readBytes(header, sizeof(header));
    http.end();
    if (n < sizeof(header)) {
        msgOut = "Arquivo curto demais.";
        return false;
    }
    if (header[0] != 0xE9) {
        msgOut = "Magic byte 0x" + String(header[0], HEX) + " invalido. Esperado: 0xE9.";
        return false;
    }
    if (header[8] == 0xEE || (header[11] != 0x40 && header[11] != 0x3F)) {
        msgOut = "Imagem nao e ESP8266 (header de ESP32?). Update abortado.";
        return false;
    }
    return true;
}

bool OtaManager::update(const OtaCommand& cmd, String& msgOut) {
    Serial.printf("%s Versao nova: %s (SHA %s) | atual: %s (SHA %s)\n", TAG,
                  cmd.version.c_str(), cmd.sha.c_str(), s_version.c_str(), s_sha.c_str());

    if (s_sha.length() > 0 && cmd.sha.equalsIgnoreCase(s_sha)) {
        msgOut = "Stopping OTA download due same firmware SHA256";
        return false;
    }
    if (!checkImageHeader(cmd.url, msgOut)) return false;

    // bootAudit do proximo boot decide sucesso/falha
    if (!writeLines(PENDING_FILE, cmd.version, cmd.sha, s_md5)) {
        msgOut = "LittleFS: falha ao gravar ota_pending";
        return false;
    }

    std::unique_ptr<WiFiClient> client = HttpService::makeClient(cmd.url);
    ESPhttpUpdate.rebootOnUpdate(false);
    ESPhttpUpdate.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
    ESPhttpUpdate.onProgress([](int cur, int total) {
        static int lastPct = -1;
        int pct = total > 0 ? (cur * 100) / total : 0;
        if (pct / 10 != lastPct / 10) {
            Serial.printf("[OtaManager] Download %d%% (%d/%d bytes)\n", pct, cur, total);
            lastPct = pct;
        }
    });

    Serial.printf("%s Atualizacao encontrada! Baixando v%s | free sketch space: %u bytes\n",
                  TAG, cmd.version.c_str(), ESP.getFreeSketchSpace());
    t_httpUpdate_return ret = ESPhttpUpdate.update(*client, cmd.url, s_version);

    switch (ret) {
        case HTTP_UPDATE_OK:
            Serial.printf("%s Imagem gravada. Reiniciando para aplicar v%s...\n", TAG, cmd.version.c_str());
            delay(500);
            ESP.restart();
            return true;   // unreachable

        case HTTP_UPDATE_NO_UPDATES:
            msgOut = "Servidor respondeu 304 (sem update)";
            break;

        case HTTP_UPDATE_FAILED:
        default:
            msgOut = "Falha OTA (" + String(ESPhttpUpdate.getLastError()) + "): " +
                     ESPhttpUpdate.getLastErrorString();
            break;
    }
    LittleFS.remove(PENDING_FILE);
    return false;
}

bool OtaManager::isSuccessAnnouncePending() { return LittleFS.exists(ANNOUNCE_FILE); }
void OtaManager::clearSuccessAnnounce()     { LittleFS.remove(ANNOUNCE_FILE); }

void OtaManager::savePendingFailure(const String& msg, const String& version, const String& sha) {
    String line = msg;
    line.replace('\n', ' ');
    writeLines(FAIL_FILE, line, version, sha);
}

bool OtaManager::peekPendingFailure(String& msg, String& extraJson) {
    String version, sha;
    if (!readLines(FAIL_FILE, msg, version, sha)) return false;

    extraJson = "{\"attempted_sha\": \"" + sha + "\", \"attempted_version\": \"" + version + "\"}";
    return true;
}

void OtaManager::clearPendingFailure() { LittleFS.remove(FAIL_FILE); }
