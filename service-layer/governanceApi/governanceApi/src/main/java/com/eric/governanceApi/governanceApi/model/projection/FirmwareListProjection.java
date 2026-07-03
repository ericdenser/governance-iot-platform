package com.eric.governanceApi.governanceApi.model.projection;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;

public record FirmwareListProjection(

    String firmwareId,
    String firmwareName,
    String description,
    String ownerGroupId,
    boolean provisioningFirmware,
    Instant createdAt,
    String createdByActorId,
    String createdByUsername,
    Long versionsCount,

    // Campos da última versão — podem vir null se o firmware não tem versão
    String latestVersionId,
    String latestVersionString,
    FirmwareStatus latestStatus,
    Instant latestUploadedAt,
    String latestCreatedByUsername,
    Integer latestDeployCount,
    Long latestSizeBytes
) {

}
