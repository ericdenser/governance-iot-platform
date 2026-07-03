package com.eric.governanceApi.governanceApi.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "firmwares")
@Getter
@Setter
@NoArgsConstructor
public class Firmware extends AuthoredEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firmware_id", unique = true, nullable = false, updatable = false)
    private String firmwareId;

    @Column(name = "firmware_name", nullable = false)
    private String firmwareName;

    @Column(length = 1000)
    private String description;

    // null = platform firmware (ADMIN only); non-null = group-owned firmware
    @Column(name = "owner_group_id")
    private String ownerGroupId;

    private boolean provisioningFirmware = false;

    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "firmware", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FirmwareVersion> versions = new ArrayList<>();

    @PrePersist
    private void generateFirmwareId() {
        if (this.firmwareId == null) {
            this.firmwareId = UUID.randomUUID().toString();
        }
    }
}