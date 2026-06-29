package com.eric.governanceApi.governanceApi.audit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.model.entity.AuditLog;
import com.eric.governanceApi.governanceApi.repository.AuditLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actorId, String actorUsername, AuditAction action,
                       String targetType, String targetId, String details,
                       boolean success, String errorMessage) {
        try {
            AuditLog entry = new AuditLog();
            entry.setActorId(actorId);
            entry.setActorUsername(actorUsername);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetails(details);
            entry.setSuccess(success);
            entry.setErrorMessage(errorMessage);
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Falha ao registrar audit log para acao {}: {}", action, e.getMessage());
        }
    }
}
