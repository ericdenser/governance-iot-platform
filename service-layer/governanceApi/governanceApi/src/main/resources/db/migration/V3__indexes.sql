-- One provisioning firmware per chip (replaces the old "only one overall" index)
CREATE UNIQUE INDEX IF NOT EXISTS uq_provisioning_per_chip
    ON firmwares (target_chip) WHERE provisioning_firmware = true;

-- Indexes from the pre-Flyway migrations, which never ran
CREATE INDEX IF NOT EXISTS idx_audit_log_performed_at ON audit_log (performed_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_id ON audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action);
CREATE INDEX IF NOT EXISTS idx_user_group_assignment_user ON user_group_assignment (keycloak_user_id);
CREATE INDEX IF NOT EXISTS idx_device_group_membership_group ON device_group_membership (group_id);
CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices (last_seen);
CREATE INDEX IF NOT EXISTS idx_command_record_batch ON command_record (batch_id);
CREATE INDEX IF NOT EXISTS idx_command_batch_sent_at ON command_batch (sent_at DESC);

-- List screens filter and sort by date
CREATE INDEX IF NOT EXISTS idx_event_registry_ocurred_at ON event_registry (ocurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_error_record_sent_at ON error_record (sent_at DESC);
