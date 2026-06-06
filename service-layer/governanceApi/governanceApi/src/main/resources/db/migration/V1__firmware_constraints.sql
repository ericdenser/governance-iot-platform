CREATE UNIQUE INDEX IF NOT EXISTS uq_only_one_provisioning
ON firmwares (provisioning_firmware)
WHERE provisioning_firmware = true;