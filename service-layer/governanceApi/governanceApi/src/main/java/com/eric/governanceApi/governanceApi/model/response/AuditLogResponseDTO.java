package com.eric.governanceApi.governanceApi.model.response;

import java.time.Instant;

import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.model.entity.AuditLog;

public record AuditLogResponseDTO(
        String auditId,
        String actorId,
        String actorUsername,
        AuditAction action,
        String targetType,
        String targetId,
        String details,
        boolean success,
        String errorMessage,
        Instant performedAt
) {
    public static AuditLogResponseDTO from(AuditLog log) {
        return new AuditLogResponseDTO(
                log.getAuditId(),
                log.getActorId(),
                log.getActorUsername(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                log.getDetails(),
                log.isSuccess(),
                log.getErrorMessage(),
                log.getPerformedAt()
        );
    }
}
