package com.eric.eventhandler.event_handler.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.enums.EventType;

//Payload enviado via webhook para o MDM.
@Data
@Builder
public class DeviceEvent {
    private EventType eventName;
    private String deviceMac;
    private DeviceState previousStatus;
    private DeviceState newStatus;
    private StatusDTO deviceInfo;
    private LocalDateTime timestamp;
}