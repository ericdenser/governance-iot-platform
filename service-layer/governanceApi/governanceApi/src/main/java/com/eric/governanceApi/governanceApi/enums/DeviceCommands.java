package com.eric.governanceApi.governanceApi.enums;

public enum DeviceCommands {
    UPDATE("ota"),
    REBOOT("reboot"),
    DEEP_SLEEP("sleep");

    private final String mqttSubtopic;

    DeviceCommands(String mqttSubtopic) {
        this.mqttSubtopic = mqttSubtopic;
    }

    // O Agent publica em: commands/<MAC>/<subtopic>
    public String getSubtopic() {
        return mqttSubtopic;
    }
}