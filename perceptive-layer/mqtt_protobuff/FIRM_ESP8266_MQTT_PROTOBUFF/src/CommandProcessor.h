#ifndef COMMANDPROCESSOR_H
#define COMMANDPROCESSOR_H

#include <Arduino.h>

struct OtaCommand {
    String version;
    String url;
    String sha;
};

// So UPDATE. Callback so enfileira, o download roda no loop()
class CommandProcessor {
public:
    // {"command":"UPDATE","payload":{"version":"..","url":"..","versionSHA":".."}}
    static bool manage(const String& payload);

    // Retira o UPDATE pendente (se houver)
    static bool takePendingOta(OtaCommand& out);
};

#endif
