package com.eric.governanceApi.governanceApi.model.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;

import lombok.Data;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "devices")
@Data
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", unique = true, nullable = false, updatable = false)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "firmware")
    private Firmware firmware;

    @Column(name = "mac_address", unique = true)
    private String macAddress;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen")
    private Instant lastSeen;

    @OneToMany(mappedBy = "targetDevice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CommandRecord> commandRecords = new ArrayList<>();

    @OneToOne(mappedBy = "device", cascade = CascadeType.ALL)
    private ProvisioningToken provisioningToken;

    @OneToOne(mappedBy = "device", cascade = CascadeType.ALL)
    private DeviceCertificate certificate;

    @ElementCollection
    @CollectionTable(
        name = "device_sensors_status",
        joinColumns = @JoinColumn(name = "device_id")

    )
    @MapKeyColumn(name = "sensor_name")
    @Column(name = "active")
    Map<String, Boolean> sensorStatus = new HashMap<>();


    @PrePersist
    private void generateDeviceId() {
        if (this.deviceId == null) {
            this.deviceId = UUID.randomUUID().toString();
        }
    }

    public void addCommandRecord(CommandRecord command) {
        this.commandRecords.add(command);
        command.setTargetDevice(this);
    }

    public void removeCommandRecord(CommandRecord command) {
        this.commandRecords.remove(command);
        command.setTargetDevice(null);
    }

}
