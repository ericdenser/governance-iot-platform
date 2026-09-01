#!/usr/bin/env bash
# Publica bootloader + partition table no MinIO.
# Le os .bin do host em $PROVISIONING_BINS_VOLUME (default ./platform-bins).
# Re-rodar quando atualizar ESP-IDF ou partition layout.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
set -a; source .env; set +a

HOST_BINS_DIR="${PROVISIONING_BINS_VOLUME:-./platform-bins}"
# resolve relative -> absoluto
case "$HOST_BINS_DIR" in
  /*) ;;
  *)  HOST_BINS_DIR="$REPO_ROOT/${HOST_BINS_DIR#./}" ;;
esac

BOOTLOADER="$HOST_BINS_DIR/bootloader.bin"
PARTITION="$HOST_BINS_DIR/partition-table.bin"

[ -f "$BOOTLOADER" ] || { echo "ERRO: bootloader nao encontrado em $BOOTLOADER"; exit 1; }
[ -f "$PARTITION" ]  || { echo "ERRO: partition-table nao encontrada em $PARTITION"; exit 1; }

docker run --rm --network "${DOCKER_NETWORK:-app-net}" \
  -v "$BOOTLOADER:/bins/bootloader.bin:ro" \
  -v "$PARTITION:/bins/partition-table.bin:ro" \
  --entrypoint /bin/sh minio/mc:RELEASE.2025-04-16T18-13-26Z -c "
  mc alias set gov ${MINIO_ENDPOINT} '$MINIO_ACCESS_KEY' '$MINIO_SECRET_KEY' &&
  mc cp /bins/bootloader.bin gov/$MINIO_BUCKET/platform/bootloader.bin &&
  mc cp /bins/partition-table.bin gov/$MINIO_BUCKET/platform/partition-table.bin &&
  mc ls gov/$MINIO_BUCKET/platform/ &&
  echo PLATFORM_BINS_OK
"

