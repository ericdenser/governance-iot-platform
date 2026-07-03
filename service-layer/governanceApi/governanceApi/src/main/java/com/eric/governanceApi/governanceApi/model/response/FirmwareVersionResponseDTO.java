package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import java.util.List;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;

public record FirmwareVersionResponseDTO (
    String versionId,
    String firmwareId,
    String firmwareName,
    String ownerGroupId,           // herdado do product — pra SPA mostrar badge de grupo
    boolean provisioningFirmware,  // herdado do product — pra SPA mostrar badge "Provisioning"
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
    String createdByActorId,
    String createdByUsername
) {

    public static FirmwareVersionResponseDTO from(FirmwareVersion v) {

        List<FirmwareSensorConfigResponseDTO> sensors =
            v.getSensorConfigs().stream()
                .map(cfg -> new FirmwareSensorConfigResponseDTO(
                    cfg.getSensor().getName(),
                    cfg.getPin()
                ))
                .toList();

        return new FirmwareVersionResponseDTO(
            v.getFirmwareVersionId(),
            v.getFirmware().getFirmwareId(),
            v.getFirmware().getFirmwareName(),
            v.getFirmware().getOwnerGroupId(),
            v.getFirmware().isProvisioningFirmware(),
            v.getVersion(),
            v.getFilename(),
            v.getOriginalFilename(),
            v.getSha256(),
            v.getSizeBytes(),
            v.getDownloadUrl(),
            v.getReleaseNotes(),
            v.getStatus(),
            sensors,
            v.getUploadedAt(),
            v.getDeployCount(),
            v.getCreatedByActorId(),
            v.getCreatedByUsername());
    }

}
