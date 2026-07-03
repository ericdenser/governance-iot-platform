package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.status.DeviceStatus;

public record DeviceSummaryDTO(
    String deviceId,
    String name,
    DeviceStatus status,
    String macAddress,

    // contexto do firmware
    String firmwareId,  // pode ser null caso device esteja pending
    String firmwareName,
    String firmwareVersionId,
    String firmwareVersion,

    Instant createdAt,
    Instant lastSeen,
    String issuedByActorId,
    String issuedByUsername
) {

}
