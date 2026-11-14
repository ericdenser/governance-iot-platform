-- Sensors of a version come from the manifest compiled into the binary (several pins each)
ALTER TABLE firmware_sensor_config DROP COLUMN pin;
ALTER TABLE firmware_sensor_config ADD COLUMN pins varchar(255);
ALTER TABLE firmware_sensor_config ADD COLUMN metrics varchar(255);
