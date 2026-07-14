package com.eric.eventhandler.event_handler.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

import com.eric.eventhandler.event_handler.enums.DeviceState;
import com.eric.eventhandler.event_handler.model.dto.StatusDTO;

/**
 * Snapshot do último estado conhecido de cada device.
 * Usado para decidir se houve uma transição relevante.
 */
@Entity
@Table(name = "device_snapshots")
@Data
@NoArgsConstructor
public class DeviceSnapshot {

    @Id
    private String deviceId;


    private String mac;    

    @Enumerated(EnumType.STRING)
    private DeviceState status;
    
    private String firmwareVersion;
    private String ssid;
    private Instant lastSeen;
    private String activeSensors;

    public void updateFrom(StatusDTO dto) {
        this.mac = dto.mac();
        this.status = dto.status();
        this.firmwareVersion = dto.firmwareVersion();
        this.ssid = dto.ssid();
        this.lastSeen = dto.deviceTimestamp() != null ? dto.deviceTimestamp() : Instant.now();
        this.activeSensors = dto.activeSensors();
    }
}