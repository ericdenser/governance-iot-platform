SHELL := /bin/bash

# Diretorio deste Makefile (funciona chamando de qualquer subdir).
REPO_ROOT := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
ENV_FILE  := $(REPO_ROOT)/.env

# Nome da rede docker compartilhada entre composes.
DOCKER_NETWORK := app-net

# Composes de infra e serviços.
INFRA_COMPOSE := $(REPO_ROOT)/docker-compose.yml
SERVICE_COMPOSES := \
	$(REPO_ROOT)/service-layer/governanceApi/governanceApi/docker-compose.yml \
	$(REPO_ROOT)/service-layer/agents/agent-mqtt/docker-compose.yml \
	$(REPO_ROOT)/service-layer/event-handler/docker-compose.yml \
	$(REPO_ROOT)/service-layer/datalogger/datalogger/docker-compose.yml \
	$(REPO_ROOT)/application-layer/bff/docker-compose.yml \
	$(REPO_ROOT)/application-layer/spa/docker-compose.yml

# Cores ANSI pra outputs.
CYAN   := \033[36m
GREEN  := \033[32m
YELLOW := \033[33m
RED    := \033[31m
RESET  := \033[0m

.DEFAULT_GOAL := help

.PHONY: help check-env init-secrets init-certs clean-certs net build up up-build down restart ps status logs clean setup upload-bin

help:  ## Lista todos os comandos disponiveis
	@printf "$(CYAN)Governance IoT — comandos disponiveis:$(RESET)\n\n"
	@awk 'BEGIN {FS = ":.*?## "} \
		/^[a-zA-Z_-]+:.*?## / { printf "  $(GREEN)%-14s$(RESET) %s\n", $$1, $$2 }' \
		$(MAKEFILE_LIST)
	@printf "\n$(YELLOW)Uso:$(RESET) make <comando>\n"

check-env:  ## Valida que .env existe e tem os campos obrigatorios
	@if [ ! -f "$(ENV_FILE)" ]; then \
		printf "$(RED)ERRO:$(RESET) .env nao encontrado.\n"; \
		printf "Rode: $(CYAN)cp .env.example .env$(RESET) e edite antes de continuar.\n"; \
		exit 1; \
	fi
	@if ! grep -qE "^HOST_IP=..+$$" "$(ENV_FILE)"; then \
		printf "$(RED)ERRO:$(RESET) HOST_IP nao setado em .env.\n"; \
		printf "Edite o arquivo e coloque o IP/hostname da maquina host.\n"; \
		exit 1; \
	fi
	@printf "$(GREEN)OK$(RESET) .env valido\n"

init-secrets:  ## Gera secrets aleatorios (Keycloak clients + PKI + INFRA_API_KEY) no .env (idempotente)
	@if [ ! -f "$(ENV_FILE)" ]; then \
		printf "$(RED)ERRO:$(RESET) .env nao encontrado. Rode $(CYAN)cp .env.example .env$(RESET) antes.\n"; \
		exit 1; \
	fi
	@# 32 chars alphanum: clients Keycloak + senhas dos p12 (consumidas por init_certs.sh e runtime).
	@for var in GOVAPI_CLIENT_SECRET BFF_CLIENT_SECRET AGENT_MQTT_CLIENT_SECRET \
	            CA_PASSWORD AGENT_KEYSTORE_PASS AGENT_TRUSTSTORE_PASS; do \
		current=$$(grep -E "^$$var=" "$(ENV_FILE)" | cut -d= -f2-); \
		if [ -z "$$current" ] || [ "$$current" = "changeme" ]; then \
			new=$$(openssl rand -base64 32 | tr -d '=+/\n' | cut -c1-32); \
			if grep -qE "^$$var=" "$(ENV_FILE)"; then \
				sed -i "s|^$$var=.*|$$var=$$new|" "$(ENV_FILE)"; \
			else \
				echo "$$var=$$new" >> "$(ENV_FILE)"; \
			fi; \
			printf "$(GREEN)OK$(RESET) $$var gerado (32 chars aleatorios)\n"; \
		else \
			printf "$(YELLOW)SKIP$(RESET) $$var ja setado (nao sobrescreve)\n"; \
		fi; \
	done
	@# UUID pra INFRA_API_KEY (shared secret govApi <-> infra-executor no reload da CRL).
	@current=$$(grep -E "^INFRA_API_KEY=" "$(ENV_FILE)" | cut -d= -f2-); \
	if [ -z "$$current" ] || [ "$$current" = "changeme" ]; then \
		new=$$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen); \
		if grep -qE "^INFRA_API_KEY=" "$(ENV_FILE)"; then \
			sed -i "s|^INFRA_API_KEY=.*|INFRA_API_KEY=$$new|" "$(ENV_FILE)"; \
		else \
			echo "INFRA_API_KEY=$$new" >> "$(ENV_FILE)"; \
		fi; \
		printf "$(GREEN)OK$(RESET) INFRA_API_KEY gerado (UUID)\n"; \
	else \
		printf "$(YELLOW)SKIP$(RESET) INFRA_API_KEY ja setado\n"; \
	fi
	@printf "$(CYAN)Secrets prontos.$(RESET) Proximo: $(CYAN)make init-certs$(RESET) (PKI mTLS) e $(CYAN)make up-build$(RESET)\n"

init-certs:  ## Gera Root CA + certs (broker, agent) e distribui pros lugares certos (idempotente)
	@bash $(REPO_ROOT)/scripts/init_certs.sh

clean-certs:  ## Apaga PKI local (forca regeneracao no proximo init-certs)
	@if command -v docker >/dev/null 2>&1; then \
		docker run --rm -v $(REPO_ROOT)/mosquitto/config:/cfg alpine:3 \
			sh -c "rm -rf /cfg/certs /cfg/regras_acesso.acl" >/dev/null 2>&1 || true; \
	fi
	@rm -rf $(REPO_ROOT)/keys
	@printf "$(GREEN)OK$(RESET) certs limpos. Rode $(CYAN)make init-certs$(RESET) pra regenerar.\n"

upload-bin: ## Upload dos bins bootloader e partition
	@bash ${REPO_ROOT}/scripts/upload_platform_bins.sh

net:  ## Cria a rede docker compartilhada (idempotente)
	@if ! docker network inspect $(DOCKER_NETWORK) >/dev/null 2>&1; then \
		printf "$(CYAN)Criando rede docker '$(DOCKER_NETWORK)'...$(RESET)\n"; \
		docker network create $(DOCKER_NETWORK); \
	else \
		printf "$(GREEN)OK$(RESET) rede '$(DOCKER_NETWORK)' ja existe\n"; \
	fi

build: check-env  ## Rebuilda imagens dos servicos (use quando codigo mudou)
	@for f in $(SERVICE_COMPOSES); do \
		printf "$(CYAN)=== build: $$f ===$(RESET)\n"; \
		docker compose --env-file $(ENV_FILE) -f $$f build; \
	done

up: check-env net  ## Sobe infra + servicos SEM rebuild (usa imagens existentes)
	@printf "$(CYAN)=== infra ===$(RESET)\n"
	@docker compose --env-file $(ENV_FILE) -f $(INFRA_COMPOSE) up -d
	@printf "$(CYAN)Aguardando postgres healthy...$(RESET)\n"
	@for i in $$(seq 1 30); do \
		if docker inspect --format '{{.State.Health.Status}}' iot-postgres 2>/dev/null | grep -q healthy; then \
			printf "$(GREEN)OK$(RESET) postgres healthy\n"; break; \
		fi; sleep 2; \
	done
	@printf "$(CYAN)Aguardando keycloak healthy (pode levar 30-60s no primeiro boot)...$(RESET)\n"
	@for i in $$(seq 1 60); do \
		if docker inspect --format '{{.State.Health.Status}}' iot-keycloak 2>/dev/null | grep -q healthy; then \
			printf "$(GREEN)OK$(RESET) keycloak healthy\n"; break; \
		fi; \
		if [ $$i -eq 60 ]; then \
			printf "$(RED)ERRO:$(RESET) keycloak nao ficou healthy em 120s. Rode $(CYAN)docker logs iot-keycloak$(RESET) pra investigar.\n"; \
			exit 1; \
		fi; \
		sleep 2; \
	done
	@# Sobe apps sequencialmente com --wait: cada um bloqueia ate healthy antes
	@# do proximo subir. Se um falhar (crash/unhealthy), aborta com erro claro
	@# e nao continua puxando CPU pros seguintes.
	@for f in $(SERVICE_COMPOSES); do \
		printf "$(CYAN)=== up: $$f (aguardando healthy)$(RESET)\n"; \
		if ! docker compose --env-file $(ENV_FILE) -f $$f up -d --wait --wait-timeout 120; then \
			printf "$(RED)FALHOU:$(RESET) $$f nao ficou healthy em 120s.\n"; \
			printf "Debug: $(CYAN)docker logs \$$(docker compose -f $$f ps -q | head -1)$(RESET)\n"; \
			exit 1; \
		fi; \
	done
	@$(MAKE) --no-print-directory status

up-build: build up  ## Build + up (usar primeira vez ou apos mudar codigo)

down:  ## Para todos os containers (mantem volumes)
	@for f in $(SERVICE_COMPOSES) $(INFRA_COMPOSE); do \
		printf "$(YELLOW)=== down: $$f ===$(RESET)\n"; \
		docker compose --env-file $(ENV_FILE) -f $$f down; \
	done

restart: check-env  ## Reinicia todos os containers sem rebuild
	@for f in $(INFRA_COMPOSE) $(SERVICE_COMPOSES); do \
		printf "$(CYAN)=== restart: $$f ===$(RESET)\n"; \
		docker compose --env-file $(ENV_FILE) -f $$f restart; \
	done
	@$(MAKE) --no-print-directory ps

ps:  ## Lista status dos containers do stack
	@printf "$(CYAN)Containers do stack:$(RESET)\n"
	@docker ps --filter "name=iot-" --filter "name=infra_executor" \
		--format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

status:  ## Status detalhado (health + restarts) de todos containers do stack
	@printf "$(CYAN)Health + restart count por container:$(RESET)\n"
	@for c in iot-postgres iot-keycloak iot-redis iot-redis-streams iot-broker iot-minio iot-influxdb govapi agent-mqtt event-handler datalogger bff spa; do \
		if docker inspect $$c >/dev/null 2>&1; then \
			state=$$(docker inspect --format '{{.State.Status}}' $$c); \
			health=$$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' $$c); \
			restarts=$$(docker inspect --format '{{.RestartCount}}' $$c); \
			color=$(GREEN); \
			[ "$$state" != "running" ] && color=$(RED); \
			[ "$$health" = "unhealthy" ] && color=$(RED); \
			[ "$$health" = "starting" ] && color=$(YELLOW); \
			printf "  $$color%-20s$(RESET) state=%-10s health=%-10s restarts=%s\n" "$$c" "$$state" "$$health" "$$restarts"; \
		else \
			printf "  $(YELLOW)%-20s$(RESET) (nao existe)\n" "$$c"; \
		fi; \
	done

logs:  ## Logs em tempo real de UM container. Uso: make logs SVC=iot-govapi
	@if [ -z "$(SVC)" ]; then \
		printf "$(RED)ERRO:$(RESET) especifique o container. Ex: $(CYAN)make logs SVC=iot-govapi$(RESET)\n"; \
		exit 1; \
	fi
	@docker logs -f --tail 100 $(SVC)

clean:  ## PARA tudo E REMOVE volumes (DESTRUTIVO — pede confirmacao)
	@printf "$(RED)ATENCAO:$(RESET) isso vai apagar TODOS os volumes (postgres, minio, influx, redis).\n"
	@printf "Voce vai perder devices, firmwares, telemetria, sessoes.\n"
	@read -p "Tem certeza? Digite 'sim' pra continuar: " confirm; \
	if [ "$$confirm" != "sim" ]; then printf "$(YELLOW)Cancelado.$(RESET)\n"; exit 1; fi
	@for f in $(SERVICE_COMPOSES) $(INFRA_COMPOSE); do \
		docker compose --env-file $(ENV_FILE) -f $$f down -v; \
	done
	@printf "$(GREEN)OK$(RESET) tudo limpo\n"

setup: check-env init-secrets init-certs up-build  ## Bootstrap completo: secrets + PKI + build + up
	@printf "\n$(GREEN)Setup completo.$(RESET) Ver estado: $(CYAN)make status$(RESET)\n"
