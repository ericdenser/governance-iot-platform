#ifndef COMMANDPROCESSOR_H
#define COMMANDPROCESSOR_H

#include <string>

enum class CommandType {
    UPDATE,
    DEEP_SLEEP,
    REBOOT,
    UNKNOWN
};

class CommandProcessor {
public:

    static bool manage(const std::string& payload);

};

#endif