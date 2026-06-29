package com.eric.governanceApi.governanceApi.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@EntityListeners(AuthorListener.class)
@Getter
@Setter
public abstract class AuthoredEntity {

    /** Keycloak subject UUID — stable user identity. */
    @Column(name = "created_by_actor_id", updatable = false, length = 36)
    private String createdByActorId;

    /** Snapshot of preferred_username at creation time. */
    @Column(name = "created_by_username", updatable = false, length = 150)
    private String createdByUsername;
}
