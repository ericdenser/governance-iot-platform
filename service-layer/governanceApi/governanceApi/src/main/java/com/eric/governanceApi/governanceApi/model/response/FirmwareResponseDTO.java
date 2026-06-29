package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.List;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;

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
    boolean provisioningFirmware,
    String createdByActorId,
    String createdByUsername
) {

    public static FirmwareResponseDTO from(Firmware fw) {

        List<FirmwareSensorConfigResponseDTO> sensors =
            fw.getSensorConfigs().stream()
                .map(cfg -> new FirmwareSensorConfigResponseDTO(
                    cfg.getSensor().getName(),
                    cfg.getPin()
                ))
                .toList();

        return new FirmwareResponseDTO(
            fw.getFirmwareId(),
            fw.getVersion(),
            fw.getFilename(),
            fw.getOriginalFilename(),
            fw.getSha256(),
            fw.getSizeBytes(),
            fw.getDownloadUrl(),
            fw.getReleaseNotes(),
            fw.getStatus(),
            sensors,
            fw.getUploadedAt(),
            fw.getDeployCount(),
            fw.isProvisioningFirmware(),
            fw.getCreatedByActorId(),
            fw.getCreatedByUsername()
        );
    }
}