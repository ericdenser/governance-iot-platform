#ifndef MQTTMANAGER_H
#define MQTTMANAGER_H

#include <functional>
#include <string>

class MqttManager {
public:
    using MessageCallback = std::function<void(const std::string& topic, const std::string& payload)>;

    static void init_mqtt();
    static void publish(const uint8_t* data, size_t len, const char* topic);
    static void subscribe(const std::string& topic, int qos = 1);
    static void setCallback(MessageCallback cb);
    
};

#endif