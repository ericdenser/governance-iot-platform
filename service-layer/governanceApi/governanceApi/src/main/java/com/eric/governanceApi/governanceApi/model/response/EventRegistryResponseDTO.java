package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.model.entity.EventRegistry;

public record EventRegistryResponseDTO(
    String eventId,
    String eventType,
    String deviceId,
    String previousStatus,
    String newStatus,
    boolean completed,
    String resultMessage,
    Instant uploadedAt
) {
    public static EventRegistryResponseDTO from(EventRegistry e) {
        return new EventRegistryResponseDTO(
            e.getEventId(),
            e.getEventName(), // campo DB continua "eventName", só o JSON muda
            e.getDevice() != null ? e.getDevice().getDeviceId() : null,
            e.getPreviousStatus(),
            e.getNewStatus(),
            e.isCompleted(),
            e.getResultMessage(),
            e.getUploadedAt()
        );
    }
}
