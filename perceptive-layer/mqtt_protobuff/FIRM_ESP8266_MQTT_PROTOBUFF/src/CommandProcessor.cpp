#include "CommandProcessor.h"
#include "HttpService.h"

static const char* TAG = "[CommandProcessor]";

static OtaCommand s_pending_ota;
static bool       s_has_pending_ota = false;

bool CommandProcessor::manage(const String& payload) {
    Serial.printf("%s RAW PAYLOAD: %s\n", TAG, payload.c_str());

    String command;
    if (!jsonGetString(payload, "command", command)) {
        Serial.printf("%s Cannot read command (not String)\n", TAG);
        return false;
    }
    command.toLowerCase();

    if (command != "update") {
        Serial.printf("%s Comando ignorado ou desconhecido: %s\n", TAG, command.c_str());
        return false;
    }

    OtaCommand cmd;
    if (!jsonGetString(payload, "version", cmd.version) ||
        !jsonGetString(payload, "url", cmd.url) ||
        !jsonGetString(payload, "versionSHA", cmd.sha)) {
        Serial.printf("%s UPDATE sem version/url/versionSHA\n", TAG);
        return false;
    }

    if (s_has_pending_ota) {
        Serial.printf("%s OTA already in progress, ignoring duplicate command.\n", TAG);
        return true;
    }

    Serial.printf("%s OTA COMMAND RECEIVED FOR UPGRADING TO v%s\n", TAG, cmd.version.c_str());
    s_pending_ota     = cmd;
    s_has_pending_ota = true;
    return true;
}

bool CommandProcessor::takePendingOta(OtaCommand& out) {
    if (!s_has_pending_ota) return false;
    out = s_pending_ota;
    s_pending_ota     = OtaCommand();
    s_has_pending_ota = false;
    return true;
}
