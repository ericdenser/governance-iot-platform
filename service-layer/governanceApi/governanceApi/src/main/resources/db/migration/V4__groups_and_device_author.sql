-- Device: who provisioned it (operator who generated the flash package)
ALTER TABLE devices
    ADD COLUMN issued_by_actor_id  VARCHAR(36),
    ADD COLUMN issued_by_username  VARCHAR(150);

-- Groups
CREATE TABLE device_group (
    id                   BIGSERIAL    PRIMARY KEY,
    group_id             VARCHAR(36)  NOT NULL UNIQUE,
    name                 VARCHAR(100) NOT NULL UNIQUE,
    description          VARCHAR(500),
    created_by_actor_id  VARCHAR(36),
    created_by_username  VARCHAR(150),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Device ↔ Group membership (many-to-many with metadata)
CREATE TABLE device_group_membership (
    device_id            BIGINT       NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    group_id             BIGINT       NOT NULL REFERENCES device_group(id) ON DELETE CASCADE,
    added_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    added_by_actor_id    VARCHAR(36),
    added_by_username    VARCHAR(150),
    PRIMARY KEY (device_id, group_id)
);

-- User ↔ Group assignment with fine-grained role
CREATE TABLE user_group_assignment (
    keycloak_user_id      VARCHAR(36)  NOT NULL,
    group_id              BIGINT       NOT NULL REFERENCES device_group(id) ON DELETE CASCADE,
    role                  VARCHAR(20)  NOT NULL,
    assigned_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    assigned_by_actor_id  VARCHAR(36),
    assigned_by_username  VARCHAR(150),
    PRIMARY KEY (keycloak_user_id, group_id)
);

CREATE INDEX idx_user_group_assignment_user ON user_group_assignment (keycloak_user_id);
CREATE INDEX idx_device_group_membership_group ON device_group_membership (group_id);
