package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.DeviceError;
import com.eric.governanceApi.governanceApi.enums.status.ErrorStatus;
import com.eric.governanceApi.governanceApi.model.entity.ErrorRecord;

public record ErrorRecordResponseDTO(
    Long id,
    String deviceId,
    DeviceError error,
    ErrorStatus status,
    Instant reportedAt,
    String message,
    String details,
    Instant fixedAt
) {
    public static ErrorRecordResponseDTO from(ErrorRecord e) {
        return new ErrorRecordResponseDTO(
            e.getId(),
            e.getDevice().getDeviceId(),
            e.getError(),
            e.getStatus(),
            e.getReportedAt(),
            e.getMessage(),
            e.getDetails(),
            e.getFixedAt()
        );
    }
}
