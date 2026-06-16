#ifndef PAYLOADMANAGER_H
#define PAYLOADMANAGER_H

#include <string>

class PayloadManager {

private:
    std::string payload;
    bool isFirst;
    bool isClosed = false;

    void addComma();

public:

    PayloadManager();

    void add(const std::string& key, const std::string& value);
    void add(const std::string& key, const char* value);
    void add(const std::string& key, int value);
    void add(const std::string& key, float value);
    void add(const std::string& key, double value);
    void add(const std::string& key, bool value);
    void addObject(const std::string& key, const std::string& jsonObject);

    const char* build();

    std::string getString() const;

    void clear();

};

#endif