package com.eric.governanceApi.governanceApi.model.dto;

import java.time.Instant;

// DTO enviado pelo event-handler quando um evento é disparado
public record DeviceEventWebhookDTO(

    String eventType,
    String deviceId,
    String previousStatus,
    String newStatus,
    DeviceInfoDTO deviceInfo,
    Instant timestamp

) {
    
    
}