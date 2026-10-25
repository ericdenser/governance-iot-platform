#include "HttpService.h"
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecureBearSSL.h>

std::unique_ptr<WiFiClient> HttpService::makeClient(const String& url) {
    if (url.startsWith("https://")) {
        auto* secure = new BearSSL::WiFiClientSecure();
        secure->setInsecure();
        return std::unique_ptr<WiFiClient>(secure);
    }
    return std::unique_ptr<WiFiClient>(new WiFiClient());
}

bool HttpService::post(const String& url, const String& payload, String& response, String& errOut,
                       const char* contentType) {
    std::unique_ptr<WiFiClient> client = makeClient(url);

    HTTPClient http;
    http.setTimeout(10000);
    if (!http.begin(*client, url)) {
        errOut = "http.begin falhou: " + url;
        return false;
    }
    http.addHeader("Content-Type", contentType);

    int code = http.POST(payload);
    if (code <= 0) {
        errOut = "POST " + url + " falhou: " + HTTPClient::errorToString(code);
        http.end();
        return false;
    }
    response = http.getString();
    http.end();

    if (code < 200 || code >= 300) {
        errOut = "HTTP " + String(code) + ": " + response;
        return false;
    }
    return true;
}

// Indice logo apos "key":, -1 se nao achar
static int jsonFindValue(const String& json, const char* key) {
    String needle = String('"') + key + '"';
    int idx = json.indexOf(needle);
    if (idx < 0) return -1;
    idx += needle.length();
    while (idx < (int)json.length() && (json[idx] == ' ' || json[idx] == ':')) idx++;
    return idx < (int)json.length() ? idx : -1;
}

bool jsonGetString(const String& json, const char* key, String& out) {
    int idx = jsonFindValue(json, key);
    if (idx < 0 || json[idx] != '"') return false;
    int end = json.indexOf('"', idx + 1);
    if (end < 0) return false;
    out = json.substring(idx + 1, end);
    return true;
}

bool jsonGetLong(const String& json, const char* key, long& out) {
    int idx = jsonFindValue(json, key);
    if (idx < 0 || !(isDigit(json[idx]) || json[idx] == '-')) return false;
    out = strtol(json.c_str() + idx, nullptr, 10);
    return true;
}
