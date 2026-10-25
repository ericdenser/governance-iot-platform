#ifndef MQTTMANAGER_H
#define MQTTMANAGER_H

#include <Arduino.h>

// JWT no username; reconecta antes do token expirar (broker revalida a cada publish)
class MqttManager {
public:
    using MessageCallback = void (*)(const String& topic, const String& payload);

    static void init_mqtt(const String& clientId);

    // Chamar todo loop()
    static void loop();

    static bool isConnected();

    // So QoS 0 (PubSubClient)
    static bool publish(const uint8_t* data, size_t len, const char* topic);

    // Registrado e refeito a cada (re)conexao
    static void subscribe(const String& topic, int qos = 1);
    static void setCallback(MessageCallback cb);
};

#endif
