#!/usr/bin/env bash
# Builda e sobe todos os containers do stack.
# Infra sobe primeiro; espera o Postgres ficar healthy antes dos serviços (que
# tem restart:on-failure com contagem limitada e falham se subirem antes).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

echo "=== infra ==="
docker compose --env-file "$REPO_ROOT/.env" -f "$REPO_ROOT/docker-compose.yml" up -d

echo "Aguardando postgres healthy..."
for i in {1..30}; do
  if docker inspect --format '{{.State.Health.Status}}' iot-postgres 2>/dev/null | grep -q healthy; then
    echo "postgres OK"
    break
  fi
  sleep 2
done

SERVICES=(
  "service-layer/governanceApi/governanceApi/docker-compose.yml"
  "service-layer/agents/agent-mqtt/docker-compose.yml"
  "service-layer/event-handler/docker-compose.yml"
  "service-layer/datalogger/datalogger/docker-compose.yml"
  "application-layer/bff/docker-compose.yml"
  "application-layer/spa/docker-compose.yml"
)

for f in "${SERVICES[@]}"; do
  echo "=== build+up: $f ==="
  docker compose --env-file "$REPO_ROOT/.env" -f "$REPO_ROOT/$f" up -d --build
done

echo
echo "Feito. Status:"
docker ps --format 'table {{.Names}}\t{{.Status}}'
