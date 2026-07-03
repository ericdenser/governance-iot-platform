package com.eric.governanceApi.governanceApi.model.projection;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;

public record DeployableVersionProjection(
    String versionId,
    String version,
    String firmwareId,
    String firmwareName,
    String ownerGroupId,      // pra SPA mostrar de qual grupo é
    FirmwareStatus status,
    Instant uploadedAt
) {}