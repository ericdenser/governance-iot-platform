package com.eric.governanceApi.governanceApi.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.eric.governanceApi.governanceApi.enums.EventType;
import com.eric.governanceApi.governanceApi.model.request.DeviceEventWebhookDTO;
import com.eric.governanceApi.governanceApi.service.EventHandlers.DeviceEventHandler;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class EventDispatcherService {
    private final Map<EventType, DeviceEventHandler> handlers;

    @SuppressWarnings("null")
    public EventDispatcherService(List<DeviceEventHandler> allHandlers) {
        this.handlers = allHandlers.stream()
        .collect(Collectors.toMap(DeviceEventHandler::handles, h -> h));   
    }
    

    public void dispatch(DeviceEventWebhookDTO event) {
        EventType type;

        // Tenta mapear o evento do DTO para um evento conhecido
        try {
            type = EventType.valueOf(event.eventType());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown event type received {}", event.eventType());
            return;
        } catch (NullPointerException e) {
            log.warn("Eventy type is Null");
            return;
        }

        // Mapeou, vemos se existe uma logica de negocio implementada para este evento
        DeviceEventHandler handler = handlers.get(type);

        if (handler == null) {
            log.warn("No handler registered for: {}", type);
            return;
        }

        handler.process(event);
    }


}
