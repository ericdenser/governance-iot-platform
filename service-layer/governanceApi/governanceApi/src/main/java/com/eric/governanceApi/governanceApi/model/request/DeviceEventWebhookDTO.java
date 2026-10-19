package com.eric.governanceApi.governanceApi.model.request;

import java.time.Instant;
import java.util.Map;

// DTO enviado pelo event-handler quando um evento é disparado
public record DeviceEventWebhookDTO(

    String eventType,
    String deviceId,
    String previousStatus,
    String newStatus,
    DeviceInfoDTO deviceInfo,
    Instant timestamp,
    // so em eventos que nao vem de status (ex: DEVICE_HEALTH_*)
    Map<String, Object> details

) {
    
    
}