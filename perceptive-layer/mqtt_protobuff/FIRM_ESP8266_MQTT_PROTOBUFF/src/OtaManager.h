#ifndef OTAMANAGER_H
#define OTAMANAGER_H

#include <Arduino.h>
#include "CommandProcessor.h"

// Sem rollback: imagem que trava so volta pela serial.
// /fw.txt: versao + SHA amarrados ao MD5 do sketch. MD5 diferente = "unknown".
class OtaManager {
public:
    // Chamar no boot, depois do LittleFS
    static void bootAudit();

    static String currentVersion();
    static String currentSha();

    // Sucesso reinicia. Falha: false + msgOut
    static bool update(const OtaCommand& cmd, String& msgOut);

    // OTA_SUCCESSFUL ainda nao publicado
    static bool isSuccessAnnouncePending();
    static void clearSuccessAnnounce();

    // OTA_FAIL ainda nao publicado (sobrevive a reboot)
    static void savePendingFailure(const String& msg, const String& version, const String& sha);
    static bool peekPendingFailure(String& msg, String& extraJson);
    static void clearPendingFailure();

private:
    static bool checkImageHeader(const String& url, String& msgOut);
};

#endif
