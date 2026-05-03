#include "PayloadManager.h"
#include "esp_log.h"
#include <cstdio>

PayloadManager::PayloadManager() {
    clear();
}

void PayloadManager::clear() {
    payload = "{";
    isFirst = true; 
}

void PayloadManager::addComma() {
    if (!isFirst) {
        payload += ",";
    }
    isFirst = false;
}

void PayloadManager::add(const std::string& key, const std::string& value) {
    addComma();

    payload += "\"" + key + "\":\"" + value + "\"";
}

void PayloadManager::add(const std::string& key, const char* value) {
    addComma();
    payload += "\"" + key + "\":\"" + std::string(value) + "\"";
}

void PayloadManager::add(const std::string& key, int value) {
    addComma();
    payload += "\"" + key + "\":" + std::to_string(value);
}

void PayloadManager::add(const std::string& key, float value) {
    addComma();
    char buf[32];
    
    snprintf(buf, sizeof(buf), "\"%s\":%.2f", key.c_str(), value);
    payload += buf;
}

void PayloadManager::add(const std::string& key, double value) {
    addComma();
    char buf[32];
    
    snprintf(buf, sizeof(buf), "\"%s\":%.2f", key.c_str(), value);
    payload += buf;
}

void PayloadManager::add(const std::string& key, bool value) {
    addComma();
    payload += "\"" + key + "\":" + std::string(value ? "true" : "false");
}

const char* PayloadManager::build() {
    // Garante que a chave do JSON seja fechada apenas uma vez
    if (payload.back() != '}') {
        payload += "}";
    }
    return payload.c_str(); // Retorna o ponteiro char* do payload
}

std::string PayloadManager::getString() const {
    return payload + (payload.back() != '}' ? "}" : "");
}