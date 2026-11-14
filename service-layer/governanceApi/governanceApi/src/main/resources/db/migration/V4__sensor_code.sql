-- Sensors are matched by code (what the firmware reports); name is display only
ALTER TABLE sensor ADD COLUMN code varchar(24);

UPDATE sensor SET code = left(lower(regexp_replace(coalesce(name, ''), '[^A-Za-z0-9_]', '', 'g')), 24);
-- empty codes and later duplicates (the oldest sensor keeps the code)
UPDATE sensor s SET code = 'sensor_' || s.id
 WHERE s.code = '' OR EXISTS (SELECT 1 FROM sensor o WHERE o.code = s.code AND o.id < s.id);

ALTER TABLE sensor ALTER COLUMN code SET NOT NULL;
ALTER TABLE sensor ADD CONSTRAINT uq_sensor_code UNIQUE (code);

UPDATE device_sensors_status d SET sensor_name = s.code FROM sensor s WHERE s.name = d.sensor_name;
