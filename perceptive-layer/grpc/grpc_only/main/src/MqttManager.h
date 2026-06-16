#ifndef MQTTMANAGER_H
#define MQTTMANAGER_H

#include <functional>
#include <string>
#include <cstdint>
#include <cstddef>

class MqttManager {
public:
    using MessageCallback = std::function<void(const std::string& topic, const std::string& payload)>;

    static void init_mqtt(void);

    /* payload binário (protobuf) — len deve ser o tamanho real, não strlen */
    static void publish(const uint8_t* data, size_t len, const char* topic);

    static void subscribe(const std::string& topic, int qos);
    static void setCallback(MessageCallback cb);
};

#endif
