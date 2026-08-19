#!/bin/sh



set -eu

TEMPLATE=/opt/keycloak/data/import/iot-realm.json.template
TARGET=/opt/keycloak/data/import/iot-realm.json

if [ -f "$TEMPLATE" ]; then
  echo "[import-entrypoint] Rendering $TEMPLATE -> $TARGET"
  sed \
    -e "s|\${GOVAPI_CLIENT_SECRET}|${GOVAPI_CLIENT_SECRET:-}|g" \
    -e "s|\${BFF_CLIENT_SECRET}|${BFF_CLIENT_SECRET:-}|g" \
    -e "s|\${AGENT_MQTT_CLIENT_SECRET}|${AGENT_MQTT_CLIENT_SECRET:-}|g" \
    -e "s|\${HOST_IP}|${HOST_IP:-localhost}|g" \
    -e "s|\${SPA_HOST_PORT}|${SPA_HOST_PORT:-5173}|g" \
    "$TEMPLATE" > "$TARGET"
fi

if [ -f "$TARGET" ]; then
  echo "[import-entrypoint] Importing realm (--override false: pula se ja existe)"
  /opt/keycloak/bin/kc.sh import --file "$TARGET" --override false || \
    echo "[import-entrypoint] Import falhou ou realm ja existe — seguindo."
fi

echo "[import-entrypoint] Starting Keycloak: $*"
exec /opt/keycloak/bin/kc.sh "$@"
