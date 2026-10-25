#ifndef HTTPSERVICE_H
#define HTTPSERVICE_H

#include <Arduino.h>
#include <WiFiClient.h>
#include <memory>

class HttpService {
public:
    // https sem validar certificado (setInsecure)
    static std::unique_ptr<WiFiClient> makeClient(const String& url);

    // POST sincrono. true = HTTP 2xx (response preenchido); false = errOut preenchido.
    static bool post(const String& url, const String& payload, String& response, String& errOut,
                     const char* contentType = "application/json");
};

// "key":"valor" / "key":numero de JSON simples (sem escapes)
bool jsonGetString(const String& json, const char* key, String& out);
bool jsonGetLong(const String& json, const char* key, long& out);

#endif
