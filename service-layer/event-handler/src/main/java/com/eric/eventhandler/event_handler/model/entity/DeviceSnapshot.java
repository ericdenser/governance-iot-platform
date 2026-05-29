package com.eric.eventhandler.event_handler.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
    private String mac;              // PK -> MAC

    @Enumerated(EnumType.STRING)
    private DeviceState status;
    
    private Double firmwareVersion;
    private String ssid;
    private LocalDateTime lastSeen;

    public void updateFrom(StatusDTO dto) {
        this.status = dto.status();
        this.firmwareVersion = dto.firmware_version();
        this.ssid = dto.ssid();
        this.lastSeen = LocalDateTime.now();
    }
}