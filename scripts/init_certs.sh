#!/usr/bin/env bash
# init_certs.sh — bootstrap idempotente da PKI mTLS.
#
# Gera (se ausente) Root CA + certs do broker Mosquitto e do agent-mqtt,
# depois distribui pros caminhos que cada container/servico espera.
#
# Idempotente: se um arquivo ja existe em keys/, nao regenera.
# Pra recomecar do zero: make clean-certs
#
# Envs lidos do .env da raiz do repo:
#   HOST_IP                 — usado no CN + SAN IP do cert do broker
#   CA_PASSWORD             — senha do rootCA.p12
#   AGENT_KEYSTORE_PASS     — senha do agents.p12
#   AGENT_TRUSTSTORE_PASS   — senha do truststore.p12

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"

CYAN=$'\033[36m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'

log_ok()    { printf "${GREEN}OK${RESET}   %s\n" "$*"; }
log_gen()   { printf "${CYAN}GEN${RESET}  %s\n" "$*"; }
log_skip()  { printf "${YELLOW}SKIP${RESET} %s\n" "$*"; }
log_err()   { printf "${RED}ERRO${RESET} %s\n" "$*" >&2; }

if [ ! -f "$ENV_FILE" ]; then
  log_err ".env nao encontrado em $ENV_FILE"
  log_err "rode: cp .env.example .env; make init-secrets"
  exit 1
fi

# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

for v in HOST_IP CA_PASSWORD AGENT_KEYSTORE_PASS AGENT_TRUSTSTORE_PASS; do
  if [ -z "${!v:-}" ] || [ "${!v}" = "changeme" ] || [ "${!v}" = "COLOQUE_O_IP_OU_HOSTNAME_AQUI" ]; then
    log_err "$v vazio ou placeholder no .env. Rode 'make init-secrets' e edite HOST_IP antes."
    exit 1
  fi
done

KEYS_DIR="$REPO_ROOT/keys"
MOSQ_CERTS="$REPO_ROOT/brokers/mosquitto/config/certs"

mkdir -p "$KEYS_DIR" "$MOSQ_CERTS"

cd "$KEYS_DIR"

check_p12() {
  local file="$1" pass="$2" name="$3"
  [ ! -f "$file" ] && return 0
  if ! openssl pkcs12 -in "$file" -noout -passin "pass:$pass" >/dev/null 2>&1; then
    log_err "$name existe mas senha do .env nao abre (MAC invalid)."
    log_err "Cert e senha dessincronizados. Rode: make clean-certs && make init-certs"
    exit 1
  fi
}

check_p12 "$KEYS_DIR/rootCA.p12"     "$CA_PASSWORD"            "keys/rootCA.p12"
check_p12 "$KEYS_DIR/agents.p12"     "$AGENT_KEYSTORE_PASS"    "keys/agents.p12"
check_p12 "$KEYS_DIR/truststore.p12" "$AGENT_TRUSTSTORE_PASS"  "keys/truststore.p12"

# ── 1. Root CA ──────────────────────────────────────────────
if [ ! -f rootCA.key ]; then
  log_gen "rootCA.key (ECC prime256v1)"
  openssl ecparam -name prime256v1 -genkey -noout -out rootCA.key
else
  log_skip "rootCA.key"
fi

if [ ! -f rootCA.crt ]; then
  log_gen "rootCA.crt (self-signed, 10 anos)"
  openssl req -x509 -new -nodes -key rootCA.key -sha256 -days 3650 \
    -out rootCA.crt \
    -subj "/C=BR/O=Mackenzie IoT/CN=Mackenzie Root CA" \
    -addext "basicConstraints=critical,CA:TRUE"
else
  log_skip "rootCA.crt"
fi

if [ ! -f rootCA.p12 ]; then
  log_gen "rootCA.p12 (empacota CA pra Java govApi assinar CSRs de devices)"
  openssl pkcs12 -export -out rootCA.p12 \
    -inkey rootCA.key -in rootCA.crt -name "root-ca" \
    -passout "pass:$CA_PASSWORD"
else
  log_skip "rootCA.p12"
fi

# ── 2. Broker (Mosquitto) ───────────────────────────────────
if [ ! -f mosquitto.key ]; then
  log_gen "mosquitto.key + CSR (CN=$HOST_IP)"
  openssl req -new -newkey rsa:2048 -nodes \
    -keyout mosquitto.key -out mosquitto.csr \
    -subj "/C=BR/O=Mackenzie IoT/CN=$HOST_IP"
else
  log_skip "mosquitto.key"
fi

if [ ! -f mosquitto.crt ]; then
  log_gen "mosquitto.crt (assinado pela CA, SAN IP:$HOST_IP)"
  cat > san.ext <<EOF
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = IP:$HOST_IP
EOF
  openssl x509 -req -in mosquitto.csr \
    -CA rootCA.crt -CAkey rootCA.key -CAcreateserial \
    -out mosquitto.crt -days 3650 -sha256 -extfile san.ext
else
  log_skip "mosquitto.crt"
fi

# ── 3. Agent (agent-mqtt) ───────────────────────────────────
if [ ! -f agents.key ]; then
  log_gen "agents.key + CSR (CN=agents)"
  openssl req -new -newkey rsa:2048 -nodes \
    -keyout agents.key -out agents.csr \
    -subj "/C=BR/O=Mackenzie IoT/CN=agents"
else
  log_skip "agents.key"
fi

if [ ! -f agents.crt ]; then
  log_gen "agents.crt (clientAuth)"
  cat > agents.ext <<EOF
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF
  openssl x509 -req -in agents.csr \
    -CA rootCA.crt -CAkey rootCA.key -CAcreateserial \
    -out agents.crt -days 365 -sha256 -extfile agents.ext
else
  log_skip "agents.crt"
fi

if [ ! -f agents.p12 ]; then
  log_gen "agents.p12 (keystore do agent-mqtt)"
  openssl pkcs12 -export -out agents.p12 \
    -inkey agents.key -in agents.crt -name "agent-ca" \
    -passout "pass:$AGENT_KEYSTORE_PASS"
else
  log_skip "agents.p12"
fi

if [ ! -f truststore.p12 ]; then
  log_gen "truststore.p12 (Root CA como trust anchor)"
  keytool -importcert -alias root-ca -file rootCA.crt \
    -keystore truststore.p12 -storetype PKCS12 \
    -storepass "$AGENT_TRUSTSTORE_PASS" -noprompt
else
  log_skip "truststore.p12"
fi

# ── 4. Distribuir pros lugares certos ───────────────────────
printf "\n${CYAN}Distribuindo certs...${RESET}\n"

# Reset ownership do MOSQ_CERTS pro user host antes de sobrescrever
# (chown final abaixo passa pra 1883 pro mosquitto poder ler).
if command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$MOSQ_CERTS:/certs" -v "$REPO_ROOT/brokers/mosquitto/config:/cfg" \
    alpine:3 sh -c "chown -R $(id -u):$(id -g) /certs /cfg/regras_acesso.acl 2>/dev/null || true" \
    >/dev/null 2>&1 || true
fi

cp -f rootCA.crt mosquitto.crt mosquitto.key "$MOSQ_CERTS/"
log_ok "brokers/mosquitto/config/certs/ (rootCA.crt, mosquitto.crt, mosquitto.key)"

if [ ! -s "$MOSQ_CERTS/revoked.crl" ]; then
  # Mosquitto exige PEM valido (nao aceita arquivo vazio). Gera CRL sem
  # revogacoes assinado pela CA. govApi regenera este arquivo quando
  # revoga um device (via CrlScheduler).
  cat > "$KEYS_DIR/openssl-ca.cnf" <<EOF
[ ca ]
default_ca = CA_default
[ CA_default ]
database = $KEYS_DIR/index.txt
default_md = sha256
default_crl_days = 3650
EOF
  touch "$KEYS_DIR/index.txt"
  openssl ca -batch -config "$KEYS_DIR/openssl-ca.cnf" -gencrl \
    -keyfile "$KEYS_DIR/rootCA.key" -cert "$KEYS_DIR/rootCA.crt" \
    -out "$MOSQ_CERTS/revoked.crl" 2>/dev/null
  log_gen "brokers/mosquitto/config/certs/revoked.crl (vazio, assinado pela CA)"
else
  log_skip "revoked.crl (mantendo existente)"
fi

ACL_SRC="$REPO_ROOT/brokers/mosquitto/config/regras_acesso_example.acl"
ACL_DST="$REPO_ROOT/brokers/mosquitto/config/regras_acesso.acl"
if [ ! -f "$ACL_DST" ] && [ -f "$ACL_SRC" ]; then
  cp "$ACL_SRC" "$ACL_DST"
  log_gen "brokers/mosquitto/config/regras_acesso.acl (copia do example)"
else
  log_skip "regras_acesso.acl"
fi

# rootCA.crt embarcado no firmware (via EMBED_TXTFILES no CMakeLists de cada firmware).
# Copiado pro nivel do CMakeLists de cada projeto.
FIRM_PROV_DIR="$REPO_ROOT/perceptive-layer/mqtt_protobuff/FIRM_PROVISIONING_MQTT_PROTOBUFF/main"
FIRM_OP_DIR="$REPO_ROOT/perceptive-layer/mqtt_protobuff/governance_core"
for target_dir in "$FIRM_PROV_DIR" "$FIRM_OP_DIR"; do
  if [ -d "$target_dir" ]; then
    cp -f "$KEYS_DIR/rootCA.crt" "$target_dir/rootCA.crt"
    log_ok "$target_dir/rootCA.crt (firmware embed)"
  fi
done

chmod 644 "$KEYS_DIR"/rootCA.p12 "$KEYS_DIR"/agents.p12 "$KEYS_DIR"/truststore.p12
log_ok "keys/*.p12 (644 — apps Java montam via volume, UID 100 le)"

chmod 644 "$MOSQ_CERTS"/*.crt 2>/dev/null || true
chmod 640 "$MOSQ_CERTS"/*.key 2>/dev/null || true
# CRL: 0666 pra govApi (uid app, nao root) poder reescrever via bind mount.
# Seguro: CRL e publica por design (lista de certs revogados distribuida em rede).
chmod 666 "$MOSQ_CERTS"/*.crl 2>/dev/null || true
if command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$MOSQ_CERTS:/certs" -v "$REPO_ROOT/brokers/mosquitto/config:/cfg" \
    alpine:3 sh -c "chown -R 1883:1883 /certs /cfg/regras_acesso.acl && chmod 0700 /cfg/regras_acesso.acl" \
    >/dev/null 2>&1 || log_err "chown via docker falhou (mosquitto pode nao conseguir ler certs)"
fi

printf "\n${GREEN}Certs prontos.${RESET} Proximo passo: ${CYAN}make up-build${RESET}\n"
