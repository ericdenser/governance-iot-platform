#!/usr/bin/env bash
# Cria clients (gov-api, agent-mqtt, event-handler, iot-bff) no realm IOT-REALM
# do Keycloak compartilhado. Assume que o realm JÁ EXISTE.
#
# Uso na sua máquina (ou VM com docker):
#   export KC_URL=https://mackleaps.mackenzie.br/keycloak
#   export KC_USER=eric.denser
#   export KC_PASSWORD=123
#   ./keycloak-setup.sh
#
# Saída: imprime client_secrets — copie pro shared/.env.

set -euo pipefail

: "${KC_URL:?KC_URL não definido}"
: "${KC_USER:?KC_USER não definido}"
: "${KC_PASSWORD:?KC_PASSWORD não definido}"

REALM="${REALM:-IOT-REALM}"

# kcadm via docker
KC_CMD() {
  docker run --rm --network host \
    quay.io/keycloak/keycloak:26.1.2 \
    /opt/keycloak/bin/kcadm.sh "$@"
}

echo "==> Login em $REALM como $KC_USER"
KC_CMD config credentials \
  --server "$KC_URL" \
  --realm "$REALM" \
  --user "$KC_USER" \
  --password "$KC_PASSWORD"

create_client() {
  local CLIENT_ID=$1
  local CONFIDENTIAL=$2      # true|false
  local SERVICE_ACCOUNT=$3   # true|false
  local STANDARD_FLOW=$4     # true|false
  local REDIRECT_URIS=$5     # ex: 'https://mackleaps.mackenzie.br/IOT/bff/*'
  local TOKEN_LIFESPAN=$6    # segundos

  echo "==> Cria client $CLIENT_ID"

  local ARGS=(
    create clients -r "$REALM"
    -s "clientId=$CLIENT_ID"
    -s "publicClient=$([[ $CONFIDENTIAL == false ]] && echo true || echo false)"
    -s "serviceAccountsEnabled=$SERVICE_ACCOUNT"
    -s "standardFlowEnabled=$STANDARD_FLOW"
    -s "directAccessGrantsEnabled=false"
  )
  [[ -n "$REDIRECT_URIS" ]] && ARGS+=(-s "redirectUris=[\"$REDIRECT_URIS\"]")
  [[ -n "$TOKEN_LIFESPAN" ]] && ARGS+=(-s "attributes.\"access.token.lifespan\"=$TOKEN_LIFESPAN")

  KC_CMD "${ARGS[@]}" 2>/dev/null || echo "   $CLIENT_ID já existe (skipping)"

  if [[ "$CONFIDENTIAL" == "true" ]]; then
    local INTERNAL_ID
    INTERNAL_ID=$(KC_CMD get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes 2>/dev/null | tail -n1 | tr -d '\r')
    local SECRET
    SECRET=$(KC_CMD get "clients/$INTERNAL_ID/client-secret" -r "$REALM" --fields value --format csv --noquotes 2>/dev/null | tail -n1 | tr -d '\r')
    echo "   >>> $CLIENT_ID.client_secret = $SECRET"
  fi
}

create_client "gov-api"        true true  false ""                                                3600
create_client "agent-mqtt"     true true  false ""                                                3600
create_client "event-handler"  true true  false ""                                                3600
create_client "iot-bff"        true false true  "https://mackleaps.mackenzie.br/IOT/bff/*"        3600

echo ""
echo "==> Atribui role realm-management/manage-clients ao service account do gov-api"
KC_CMD add-roles -r "$REALM" \
  --uusername service-account-gov-api \
  --cclientid realm-management \
  --rolename manage-clients 2>/dev/null || echo "   role já atribuída"

echo ""
echo "✅ Setup concluído. Copie os client_secrets pro shared/.env:"
echo "   GOVAPI_CLIENT_SECRET, AGENT_MQTT_CLIENT_SECRET, EVENT_HANDLER_CLIENT_SECRET, BFF_CLIENT_SECRET"
