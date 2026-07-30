#!/usr/bin/env bash
# Reinicia todos os containers do stack sem rebuild.
# Roda a partir da raiz do repo (~/iot no lab).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

COMPOSES=(
  "docker-compose.yml"
  "service-layer/governanceApi/governanceApi/docker-compose.yml"
  "service-layer/agents/agent-mqtt/docker-compose.yml"
  "service-layer/event-handler/docker-compose.yml"
  "service-layer/datalogger/datalogger/docker-compose.yml"
  "application-layer/bff/docker-compose.yml"
  "application-layer/spa/docker-compose.yml"
)

for f in "${COMPOSES[@]}"; do
  echo "=== restart: $f ==="
  docker compose --env-file "$REPO_ROOT/.env" -f "$REPO_ROOT/$f" restart
done

echo
echo "Feito. Status:"
docker ps --format 'table {{.Names}}\t{{.Status}}'
