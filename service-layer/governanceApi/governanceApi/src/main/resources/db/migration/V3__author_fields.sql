-- Firmware: who uploaded
ALTER TABLE firmwares
    ADD COLUMN created_by_actor_id  VARCHAR(36),
    ADD COLUMN created_by_username  VARCHAR(150);

-- CommandRecord: who sent the command
ALTER TABLE command_record
    ADD COLUMN created_by_actor_id  VARCHAR(36),
    ADD COLUMN created_by_username  VARCHAR(150);

-- Sensor: who registered
ALTER TABLE sensor
    ADD COLUMN created_by_actor_id  VARCHAR(36),
    ADD COLUMN created_by_username  VARCHAR(150);

-- ProvisioningToken: who generated the flash package
ALTER TABLE provisioning_tokens
    ADD COLUMN created_by_actor_id  VARCHAR(36),
    ADD COLUMN created_by_username  VARCHAR(150);
