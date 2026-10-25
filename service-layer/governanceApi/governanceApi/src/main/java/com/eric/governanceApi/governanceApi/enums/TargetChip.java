package com.eric.governanceApi.governanceApi.enums;

// Chip alvo de um firmware e de um device
public enum TargetChip {
    ESP32_S3,
    ESP8266;

    // Requests antigos (SPA sem seletor) nao mandam chip
    public static TargetChip orDefault(TargetChip chip) {
        return chip != null ? chip : ESP32_S3;
    }
}
