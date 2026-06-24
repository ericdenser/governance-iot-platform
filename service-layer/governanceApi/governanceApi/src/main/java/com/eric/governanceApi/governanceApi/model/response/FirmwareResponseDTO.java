package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.List;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;

public record FirmwareResponseDTO(
    String firmwareId,
    String version,
    String filename,
    String originalFilename,
    String sha256,
    long sizeBytes,
    String downloadUrl,
    String releaseNotes,
    FirmwareStatus status,
    List<FirmwareSensorConfigResponseDTO> sensorConfigs,
    Instant uploadedAt,
    int deployCount,
    boolean provisioningFirmware
) {
}