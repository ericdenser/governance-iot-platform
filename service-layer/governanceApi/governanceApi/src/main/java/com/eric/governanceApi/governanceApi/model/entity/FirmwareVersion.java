package com.eric.governanceApi.governanceApi.model.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "firmware_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"firmware_id", "version"}))
@Getter
@Setter
@NoArgsConstructor
public class FirmwareVersion extends AuthoredEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firmware_version_id", nullable = false, updatable = false, unique = true)
    private String firmwareVersionId;

    @Column(nullable = false)
    private String version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "firmware_id", nullable = false)
    private Firmware firmware;

    @Column(nullable = false)
    private String filename;           

    @Column(nullable = false)
    private String originalFilename;   // nome do arquivo que o usuário subiu

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(length = 1000)
    private String releaseNotes;      

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FirmwareStatus status = FirmwareStatus.STAGED;

    @OneToMany(mappedBy = "firmwareVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FirmwareSensorConfig> sensorConfigs = new ArrayList<>();

    private int deployCount = 0; // incrementa a cada broadcast

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();


    public void decrementDeployCount() {
        this.deployCount = Math.max(0, deployCount - 1);
    }

    public void incrementDeployCount() {
        this.deployCount++;
    }

    @PrePersist
    private void generateFirmwareVersionId() {
        if (this.firmwareVersionId == null) {
            this.firmwareVersionId = UUID.randomUUID().toString();
        }
    }


}
