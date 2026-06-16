package com.eric.agentgrpc.model;

public record StatusDTO(
    String device_id,
    String mac,
    String firmware_version,
    String ssid,
    String status
) {
    // gRPC path: sem device_id (vem do tópico na stream gRPC)
    public StatusDTO(String mac, String firmware_version, String ssid, String status) {
        this(null, mac, firmware_version, ssid, status);
    }
}
