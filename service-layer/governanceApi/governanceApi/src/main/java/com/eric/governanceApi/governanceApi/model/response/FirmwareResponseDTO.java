package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;
import com.eric.governanceApi.governanceApi.model.entity.Firmware;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;

public record FirmwareResponseDTO(
    String firmwareId,
    String firmwareName,
    String description,
    String ownerGroupId,
    boolean provisioningFirmware,
    Instant createdAt,
    String createdByActorId,
    String createdByUsername,
    int versionsCount,
    FirmwareVersionSummaryDTO latestVersion
) {

    public static FirmwareResponseDTO from(Firmware fw, FirmwareVersion latest) {

        return new FirmwareResponseDTO(
            fw.getFirmwareId(),
            fw.getFirmwareName(),
            fw.getDescription(),
            fw.getOwnerGroupId(),
            fw.isProvisioningFirmware(),
            fw.getCreatedAt(),
            fw.getCreatedByActorId(),
            fw.getCreatedByUsername(),
            fw.getVersions().size(),
            latest != null ? FirmwareVersionSummaryDTO.from(latest) : null
        );
    }
}