package com.eric.governanceApi.governanceApi.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eric.governanceApi.governanceApi.enums.AuditAction;
import com.eric.governanceApi.governanceApi.model.entity.AuditLog;
import com.eric.governanceApi.governanceApi.model.response.AuditLogResponseDTO;
import com.eric.governanceApi.governanceApi.repository.AuditLogRepository;
import com.eric.governanceApi.governanceApi.repository.SearchSpecs;

import java.time.Instant;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<Page<AuditLogResponseDTO>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "performedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // Search on id, actor and target; admin-only route (regras-acesso.json)
        Specification<AuditLog> spec = Specification.allOf(
                SearchSpecs.textSearch(q, "auditId", "actorUsername", "targetId"),
                SearchSpecs.equal("action", action),
                SearchSpecs.between("performedAt", from, to));
        return ResponseEntity.ok(
                auditLogRepository.findAll(spec, pageable).map(AuditLogResponseDTO::from)
        );
    }
}
