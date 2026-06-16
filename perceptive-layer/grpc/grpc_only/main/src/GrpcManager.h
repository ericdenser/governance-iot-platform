#ifndef GRPCMANAGER_H
#define GRPCMANAGER_H

#include <functional>
#include <string>

class GrpcManager {
public:
    using MessageCallback = std::function<void(const std::string& topic, const std::string& payload)>;

    static void init();
    static void publish(const char* payload, const char* topic);
    static void setCallback(MessageCallback cb);
    static bool isReady();
};

#endif
