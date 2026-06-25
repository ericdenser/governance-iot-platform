package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.model.entity.DeviceCertificate;

public record DeviceCertificateResponseDTO(
    String deviceId,
    String serialNumber,
    Instant issuedAt,
    Instant expiresAt,
    Instant revokedAt
) {
    public static DeviceCertificateResponseDTO from(DeviceCertificate cert) {
        return new DeviceCertificateResponseDTO(
            cert.getDevice().getDeviceId(),
            cert.getSerialNumber(),
            cert.getIssuedAt(),
            cert.getExpiresAt(),
            cert.getRevokedAt()
        );
    }
}
