CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    actor_id       VARCHAR(36),
    actor_username VARCHAR(150),
    action         VARCHAR(60)  NOT NULL,
    target_type    VARCHAR(60),
    target_id      VARCHAR(255),
    details        TEXT,
    success        BOOLEAN      NOT NULL,
    error_message  VARCHAR(500),
    performed_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_performed_at ON audit_log (performed_at DESC);
CREATE INDEX idx_audit_log_actor_id     ON audit_log (actor_id);
CREATE INDEX idx_audit_log_action       ON audit_log (action);
