#!/usr/bin/env bash
# Bootstrap MinIO: cria bucket + service account com access key dedicada.
# Idempotente. Funciona em dev local (defaults) ou prod (via .env com overrides).
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; source .env; set +a

: "${MINIO_ROOT_USER:?}"
: "${MINIO_ROOT_PASSWORD:?}"
: "${MINIO_ACCESS_KEY:?}"
: "${MINIO_SECRET_KEY:?}"
: "${MINIO_BUCKET:?}"

ENDPOINT="${MINIO_ENDPOINT:-http://localhost:9000}"
NETWORK="${DOCKER_NETWORK:-host}"

docker run --rm --network "$NETWORK" --entrypoint /bin/sh minio/mc -c "
  mc alias set local '$ENDPOINT' '$MINIO_ROOT_USER' '$MINIO_ROOT_PASSWORD' &&
  mc mb --ignore-existing local/$MINIO_BUCKET &&
  if mc admin user svcacct info local '$MINIO_ACCESS_KEY' >/dev/null 2>&1; then
    echo 'Access key ja existe — ok'
  else
    mc admin user svcacct add local '$MINIO_ROOT_USER' \
      --access-key '$MINIO_ACCESS_KEY' --secret-key '$MINIO_SECRET_KEY'
  fi &&
  echo 'MINIO_INIT_OK'
"
