package com.eric.governanceApi.governanceApi.model;

import java.time.LocalDateTime;
import lombok.Data;

import com.eric.governanceApi.governanceApi.enums.DeviceStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
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

    @Column(name = "mac_address", unique = true)
    private String macAddress;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status;

    @Column(name = "created_at")
    private LocalDateTime created_at = LocalDateTime.now();

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @OneToOne(mappedBy = "device", cascade = CascadeType.ALL)
    private ProvisioningToken provisioningToken;

    @OneToOne(mappedBy = "device", cascade = CascadeType.ALL)
    private DeviceCertificate certificate;

}
