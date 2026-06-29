package com.eric.governanceApi.governanceApi.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;

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

    @Column(nullable = false, unique = true)
    private String version;

    @Column(nullable = false)
    private String filename;           

    @Column(nullable = false)
    private String originalFilename;   // nome do arquivo que o usuário subiu

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String downloadUrl;        // URL pública para o ESP baixar

    @Column(length = 1000)
    private String releaseNotes;      

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FirmwareStatus status = FirmwareStatus.STAGED;

    // TODO
    @OneToMany(mappedBy = "firmware", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FirmwareSensorConfig> sensorConfigs = new ArrayList<>();

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    private int deployCount = 0;       // incrementa a cada broadcast

    private boolean provisioningFirmware = false;

    public void decrementDeployCount() {
        this.deployCount = Math.max(0, deployCount - 1);
    }

    public void incrementDeployCount() {
        this.deployCount++;
    }

    @PrePersist
    private void generateDeviceId() {
        if (this.firmwareId == null) {
            this.firmwareId = UUID.randomUUID().toString();
        }
    }
}