package com.eric.governanceApi.governanceApi.audit;

import java.util.ArrayList;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditService auditService;

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        String actorId = null;
        String actorUsername = null;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            actorId = jwt.getSubject();
            actorUsername = jwt.getClaimAsString("preferred_username");
            if (actorUsername == null) actorUsername = jwt.getClaimAsString("client_id");
            if (actorUsername == null) actorUsername = "unknown";
        }

        // Skip audit for M2M calls (no human actor in SecurityContext)
        if (actorId == null) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        String targetId = extractTargetId(args, auditable.targetIdArg());
        String details = serializeDetails(args);

        boolean success = true;
        String errorMessage = null;

        try {
            Object result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            success = false;
            errorMessage = t.getMessage();
            throw t;
        } finally {
            auditService.record(
                actorId,
                actorUsername,
                auditable.action(),
                auditable.targetType(),
                targetId,
                details,
                success,
                errorMessage
            );
        }
    }

    private String extractTargetId(Object[] args, int index) {
        if (index < 0 || args == null || index >= args.length || args[index] == null) return null;
        return args[index].toString();
    }

    private String serializeDetails(Object[] args) {
        if (args == null || args.length == 0) return null;
        try {
            List<Object> serializable = new ArrayList<>();
            for (Object arg : args) {
                if (arg instanceof MultipartFile f) {
                    serializable.add("{\"filename\":\"" + f.getOriginalFilename() + "\",\"size\":" + f.getSize() + "}");
                } else {
                    serializable.add(arg);
                }
            }
            return MAPPER.writeValueAsString(serializable);
        } catch (Exception e) {
            log.warn("Nao foi possivel serializar args para audit: {}", e.getMessage());
            return null;
        }
    }
}
