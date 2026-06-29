package com.eric.governanceApi.governanceApi.model.entity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import jakarta.persistence.PrePersist;

public class AuthorListener {

    @PrePersist
    public void onPrePersist(AuthoredEntity entity) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            entity.setCreatedByActorId(jwt.getSubject());
            entity.setCreatedByUsername(jwt.getClaimAsString("preferred_username"));
        }
    }
}
