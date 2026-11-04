#ifndef MQTTMANAGER_H
#define MQTTMANAGER_H

#include <functional>
#include <string>

class MqttManager {
public:
    using MessageCallback = std::function<void(const std::string& topic, const std::string& payload)>;

    static void init_mqtt();
    static void publish(const uint8_t* data, size_t len, const char* topic, int qos = 0);
    // QoS 1 publish that waits for the broker PUBACK. One caller at a time.
    static bool publishConfirmed(const uint8_t* data, size_t len, const char* topic, int timeout_ms);
    static bool isConnected();
    static void tryReconnect();
    static void destroy();

    static void subscribe(const std::string& topic, int qos = 1);
    static void setCallback(MessageCallback cb);

};

#endif