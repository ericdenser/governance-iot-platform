-- Colunas de last state promovidas do Redis Hash (device:{id}:last) pelo
-- HotStatePersistenceScheduler 
-- last_seen já existe (adicionado em migration anterior).

ALTER TABLE devices
    ADD COLUMN last_latitude DOUBLE PRECISION,
    ADD COLUMN last_longitude DOUBLE PRECISION,
    ADD COLUMN last_seen_persisted_at TIMESTAMPTZ;

-- Index em last_seen: filtro comum "devices ativos nas últimas N horas"
CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen);
