package com.eric.governanceApi.governanceApi.enums.status;

public enum CommandStatus {
    PENDING,
    COMPLETED_SUCCESS,
    FAILED,
    TIMEOUT,
    // Barrado pelo govApi antes do publish (não-ACTIVE, já na versão, comando pendente)
    SKIPPED,
    // Agent não conseguiu publicar no broker MQTT
    PUBLISH_FAILED
}