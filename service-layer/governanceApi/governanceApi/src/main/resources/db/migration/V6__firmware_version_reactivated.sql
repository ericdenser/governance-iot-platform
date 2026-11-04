-- Deprecated version re-enabled by a user: deploys tell the device to ignore its own ban of this SHA
ALTER TABLE firmware_versions ADD COLUMN reactivated boolean NOT NULL DEFAULT false;

-- Rollback messages now carry the reason and the deprecation note
ALTER TABLE event_registry ALTER COLUMN result_message TYPE varchar(1000);
