#!/usr/bin/env bash
# Cria clients (gov-api, agent-mqtt, event-handler, iot-bff) no realm IOT-REALM.
# Uso:
#   export KC_URL=https://mackleaps.mackenzie.br/keycloak
#   export KC_USER=eric.denser
#   export KC_PASSWORD=xxx
#   ./keycloak-setup.sh

set -euo pipefail

: "${KC_URL:?KC_URL não definido}"
: "${KC_USER:?KC_USER não definido}"
: "${KC_PASSWORD:?KC_PASSWORD não definido}"

REALM="${REALM:-IOT-REALM}"
IMAGE="${KC_IMAGE:-quay.io/keycloak/keycloak:26.1.2}"

docker run --rm --network host \
  --entrypoint bash \
  -e KC_URL="$KC_URL" -e KC_USER="$KC_USER" -e KC_PASSWORD="$KC_PASSWORD" -e REALM="$REALM" \
  "$IMAGE" -c '
set -euo pipefail
KCADM=/opt/keycloak/bin/kcadm.sh

echo "==> Login em $REALM como $KC_USER"
$KCADM config credentials --server "$KC_URL" --realm "$REALM" --user "$KC_USER" --password "$KC_PASSWORD"

create_client() {
  local CLIENT_ID=$1 CONFIDENTIAL=$2 SERVICE_ACCOUNT=$3 STANDARD_FLOW=$4 REDIRECT_URIS=$5
  echo "==> Cria client $CLIENT_ID"
  local ARGS=(create clients -r "$REALM" \
    -s "clientId=$CLIENT_ID" \
    -s "publicClient=$([[ $CONFIDENTIAL == false ]] && echo true || echo false)" \
    -s "serviceAccountsEnabled=$SERVICE_ACCOUNT" \
    -s "standardFlowEnabled=$STANDARD_FLOW" \
    -s "directAccessGrantsEnabled=false" \
    -s "attributes.\"access.token.lifespan\"=3600")
  [[ -n "$REDIRECT_URIS" ]] && ARGS+=(-s "redirectUris=[\"$REDIRECT_URIS\"]")
  $KCADM "${ARGS[@]}" 2>/dev/null || echo "   $CLIENT_ID ja existe"
  if [[ "$CONFIDENTIAL" == "true" ]]; then
    local IID SECRET
    IID=$($KCADM get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes 2>/dev/null | tail -n1 | tr -d "\r")
    SECRET=$($KCADM get "clients/$IID/client-secret" -r "$REALM" --fields value --format csv --noquotes 2>/dev/null | tail -n1 | tr -d "\r")
    echo "   >>> $CLIENT_ID.client_secret = $SECRET"
  fi
}

create_client "gov-api"       true true  false ""
create_client "agent-mqtt"    true true  false ""
create_client "event-handler" true true  false ""
create_client "iot-bff"       true false true  "https://mackleaps.mackenzie.br/iot/bff/*"

echo ""
echo "==> Role realm-management/manage-clients pro service account do gov-api"
$KCADM add-roles -r "$REALM" --uusername service-account-gov-api --cclientid realm-management --rolename manage-clients 2>/dev/null || echo "   ja atribuida"

echo ""
echo "OK. Copie os client_secrets pro .env"
'
