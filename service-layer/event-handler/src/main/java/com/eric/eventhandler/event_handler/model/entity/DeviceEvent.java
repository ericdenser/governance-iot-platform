package com.eric.eventhandler.event_handler.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;
import com.eric.eventhandler.event_handler.model.dto.StatusDTO;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

//Payload enviado via webhook para o MDM.
@Data
@Builder
public class DeviceEvent {

    @Enumerated(EnumType.STRING)
    private EventType eventType;

    private String deviceMac;

    @Enumerated(EnumType.STRING)
    private DeviceState previousStatus;

    @Enumerated(EnumType.STRING)
    private DeviceState newStatus;

    private StatusDTO deviceInfo;


    private Instant timestamp;
}