#ifndef COMMANDPROCESSOR_H
#define COMMANDPROCESSOR_H

#include <string>

enum class CommandType {
    OTA,
    SLEEP,
    REBOOT,
    UNKNOWN
};

class CommandProcessor {
public:

    static bool manage(const std::string& topic, const std::string& payload);

private:
    static void execute();
    static void processOtaJson();
};

#endif