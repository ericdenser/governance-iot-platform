#!/usr/bin/env bash
# Bootstrap MinIO: cria bucket + service account com access key dedicada.
# Idempotente. Roda o `mc` compartilhando a network stack do container minio


set -euo pipefail

cd "$(dirname "$0")/.."
set -a; source .env; set +a

: "${MINIO_ROOT_USER:?}"
: "${MINIO_ROOT_PASSWORD:?}"
: "${MINIO_ACCESS_KEY:?}"
: "${MINIO_SECRET_KEY:?}"
: "${MINIO_BUCKET:?}"

MINIO_CONTAINER="${MINIO_CONTAINER_NAME:-iot-minio}"
MC_IMAGE="minio/mc:RELEASE.2025-04-16T18-13-26Z"

# Endpoint interno: bate em localhost:9000 do namespace de rede do minio.
INTERNAL_ENDPOINT="http://localhost:9000"

docker run --rm --network "container:${MINIO_CONTAINER}" --entrypoint /bin/sh "$MC_IMAGE" -c "
  mc alias set local '$INTERNAL_ENDPOINT' '$MINIO_ROOT_USER' '$MINIO_ROOT_PASSWORD' &&
  mc mb --ignore-existing local/$MINIO_BUCKET &&
  if mc admin user svcacct info local '$MINIO_ACCESS_KEY' >/dev/null 2>&1; then
    echo 'Access key ja existe — ok'
  else
    mc admin user svcacct add local '$MINIO_ROOT_USER' \
      --access-key '$MINIO_ACCESS_KEY' --secret-key '$MINIO_SECRET_KEY'
  fi &&
  echo 'MINIO_INIT_OK'
"
