package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.FirmwareStatus;
import com.eric.governanceApi.governanceApi.model.entity.FirmwareVersion;

public record FirmwareVersionSummaryDTO(
    String versionId,
    String version,
    FirmwareStatus status,
    Instant uploadedAt,
    String createdByUsername,
    int deployCount,
    long sizeBytes
) {
    public static FirmwareVersionSummaryDTO from(FirmwareVersion v) {
        return new FirmwareVersionSummaryDTO(
            v.getFirmwareVersionId(),
            v.getVersion(),
            v.getStatus(),
            v.getUploadedAt(),
            v.getCreatedByUsername(),
            v.getDeployCount(),
            v.getSizeBytes()
        );
    }
}
