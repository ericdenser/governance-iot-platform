-- Agrupa CommandRecords de uma única ação (1 comando para N devices).
-- Status agregado não é armazenado: derivado dos records na listagem.

CREATE TABLE command_batch (
    id                   BIGSERIAL PRIMARY KEY,
    batch_id             VARCHAR(36) NOT NULL UNIQUE,
    command_type         VARCHAR(30) NOT NULL,
    target_version_id    VARCHAR(36),
    target_version_label VARCHAR(100),
    payload              TEXT,
    sent_at              TIMESTAMPTZ NOT NULL,
    not_found_ids        TEXT,
    created_by_actor_id  VARCHAR(36),
    created_by_username  VARCHAR(150)
);

-- Nullable: records anteriores ao agrupamento não têm batch
ALTER TABLE command_record
    ADD COLUMN batch_id BIGINT REFERENCES command_batch(id);

CREATE INDEX idx_command_record_batch ON command_record(batch_id);
CREATE INDEX idx_command_batch_sent_at ON command_batch(sent_at DESC);
