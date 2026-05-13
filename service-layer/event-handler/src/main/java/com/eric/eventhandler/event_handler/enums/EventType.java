package com.eric.eventhandler.event_handler.enums;

public enum EventType {

    // Provisionamento
    DEVICE_PROVISIONED,
    DEVICE_PROVISION_FAILED,

    // Conectividade
    DEVICE_ONLINE,
    DEVICE_OFFLINE,

    // Firmware
    DEVICE_UPDATED,
    DEVICE_UPDATE_FAILED,

    // Saúde
    DEVICE_ERROR_REPORTED,
    DEVICE_REBOOTED,

    // Administrativo
    DEVICE_REVOKED
}