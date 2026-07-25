# Deploy MackCloud (VM mackleaps-apps)

## Estrutura

- `infra/` — postgres + redis x2 + minio (docker-compose próprio)
- `broker/` — mosquitto + plugin JWT (docker-compose próprio)
- `shared/.env.example` — overrides pros defaults dos composes genéricos dos serviços
- `shared/nginx-configs/iot.conf` — locations pro nginx-frontend do mackleaps-apps
- `keycloak-setup.sh` — cria clients no realm IOT-REALM

Os serviços Java (govApi/agent/event/datalogger/bff) e a SPA usam os composes genéricos que ficam junto do código de cada um (`service-layer/<serviço>/docker-compose.yml`, `application-layer/<bff|spa>/docker-compose.yml`).

## Portas host (127.0.0.1)

| Porta | Serviço | Location no nginx |
|---|---|---|
| 18884 | broker (WS) | `/IOT/mqtt` |
| 18082 | govapi | `/IOT/govapi/` |
| 18083 | bff | `/IOT/bff/` |
| 18100 | spa | `/IOT/` |
| 19000 | minio | `/IOT/minio/` |

## Deploy

### 0. Keycloak — criar clients no IOT-REALM (uma vez)

```bash
export KC_URL=https://mackleaps.mackenzie.br/keycloak
export KC_USER=eric.denser
export KC_PASSWORD=***
cd deploy/mackcloud
./keycloak-setup.sh
```

### 1. Clone na VM

```bash
git clone http://mackcloud.mackenzie.br/gitlab/EricDenser/gov-api.git ~/iot
cd ~/iot
git checkout deploy/mackcloud
```

### 2. `.env` na raiz

```bash
cp deploy/mackcloud/shared/.env.example .env
nano .env   # preencher DB_PASSWORD, *_CLIENT_SECRET, MINIO_*, INFLUX_TOKEN
```

### 3. Rede docker

```bash
docker network create iot-net
```

### 4. Provisioning-bins do govApi (uma vez)

```bash
mkdir -p service-layer/governanceApi/governanceApi/provisioning-bins
scp bootloader.bin partition-table.bin \
    owner@lspd.mackenzie.br:~/iot/service-layer/governanceApi/governanceApi/provisioning-bins/
```

### 5. Subir stacks

```bash
cd ~/iot

docker compose -f deploy/mackcloud/infra/docker-compose.yml --env-file .env up -d
sleep 20

docker compose -f deploy/mackcloud/broker/docker-compose.yml --env-file .env up -d

docker compose -f service-layer/governanceApi/governanceApi/docker-compose.yml --env-file .env up -d --build
docker compose -f service-layer/agents/agent-mqtt/docker-compose.yml           --env-file .env up -d --build
docker compose -f service-layer/event-handler/docker-compose.yml               --env-file .env up -d --build
docker compose -f service-layer/datalogger/datalogger/docker-compose.yml       --env-file .env up -d --build
docker compose -f application-layer/bff/docker-compose.yml                     --env-file .env up -d --build
docker compose -f application-layer/spa/docker-compose.yml                     --env-file .env up -d --build
```

### 6. nginx-frontend

```bash
sudo cp deploy/mackcloud/shared/nginx-configs/iot.conf /etc/nginx/includes/iot.conf
sudo nginx -t && sudo systemctl reload nginx
```

### 7. Smoke test

```bash
curl -Ik https://mackleaps.mackenzie.br/IOT/
curl -Ik https://mackleaps.mackenzie.br/IOT/bff/
curl -sk https://mackleaps.mackenzie.br/IOT/govapi/actuator/health
curl -sk https://mackleaps.mackenzie.br/IOT/minio/minio/health/live
```

## Firmware

```bash
cd perceptive-layer/mqtt_protobuff/FIRM_OPERATIONAL_MQTT_PROTOBUFF
idf.py -D SDKCONFIG_DEFAULTS="sdkconfig.defaults;sdkconfig.defaults.mackcloud" build flash
```

## Iteração

```bash
cd service-layer/governanceApi/governanceApi
docker compose --env-file ../../../.env up -d --build
```

## Troubleshooting

| Sintoma | Fix |
|---|---|
| Maven `Connection reset` no build | `-Dmaven.artifact.threads=1` no Dockerfile (já está) |
| govApi 500 no init do JwtDecoder | `JAVA_TOOL_OPTIONS` com proxy propagado |
| BFF loop redirect Keycloak | rerodar `keycloak-setup.sh` |
| Device `iss` mismatch | conferir `KC_HOSTNAME` do Keycloak = `https://mackleaps.mackenzie.br/keycloak` |
| Container OOM | ajustar `*_MEMORY_LIMIT` no `.env` |
| Porta host ocupada | mudar `*_HOST_PORT` no `.env` |
