#!/usr/bin/env bash
# Publica bootloader + partition table no MinIO.
# Re-rodar quando atualizar ESP-IDF ou partition layout.
set -euo pipefail

cd "$(dirname "$0")"
set -a; source ../.env; set +a

[ -f "$PROVISIONING_BOOTLOADER_BIN_PATH" ] || { echo "bootloader nao encontrado: $PROVISIONING_BOOTLOADER_BIN_PATH"; exit 1; }
[ -f "$PROVISIONING_PARTITION_TABLE_BIN_PATH" ] || { echo "partition table nao encontrada: $PROVISIONING_PARTITION_TABLE_BIN_PATH"; exit 1; }

docker run --rm --network "${DOCKER_NETWORK:-app-net}" \
  -v "$PROVISIONING_BOOTLOADER_BIN_PATH:/bins/bootloader.bin:ro" \
  -v "$PROVISIONING_PARTITION_TABLE_BIN_PATH:/bins/partition-table.bin:ro" \
  --entrypoint /bin/sh minio/mc:RELEASE.2025-04-16T18-13-26Z -c "
  mc alias set gov ${MINIO_ENDPOINT} '$MINIO_ACCESS_KEY' '$MINIO_SECRET_KEY' &&
  mc cp /bins/bootloader.bin gov/$MINIO_BUCKET/platform/bootloader.bin &&
  mc cp /bins/partition-table.bin gov/$MINIO_BUCKET/platform/partition-table.bin &&
  mc ls gov/$MINIO_BUCKET/platform/ &&
  echo PLATFORM_BINS_OK
"

