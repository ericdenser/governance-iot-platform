package com.eric.eventhandler.event_handler.model.dto;

import com.eric.eventhandler.event_handler.enums.EventType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubscriptionRequestDTO(

    @NotNull(message = "Subscriber is required")
    @Size(min = 1, max = 15, message = "Nome invalido (min 1 max 15 caracteres)")
    String subscriberName,

    @NotNull(message = "Event is required")
    EventType eventType,

    @NotNull(message = "Webhook is required")
    @Size(min = 10, max = 50, message = "URL invalida (min 20 max 50 caracteres)")
    String webhookUrl

) {
    
}
