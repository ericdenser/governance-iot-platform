package com.eric.governanceApi.governanceApi.model;

import com.eric.governanceApi.governanceApi.enums.FirmwareStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "firmwares")
@Data
@NoArgsConstructor
public class Firmware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private int version;

    @Column(nullable = false)
    private String filename;           // firmware_v4_a3b2c1d4e5f6.bin

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

    @Column(nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    private int deployCount = 0;       // incrementa a cada broadcast
}