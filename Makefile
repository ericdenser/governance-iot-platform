# =============================================================================
#  Governance IoT — Orquestrador de operações
#
#  Convenções:
#  - Alvos que NÃO produzem arquivo (todos aqui) sao declarados em .PHONY
#    (GNU Make Manual §4.6 — https://www.gnu.org/software/make/manual/html_node/Phony-Targets.html).
#  - SHELL := /bin/bash forca bash em vez de /bin/sh (que no Debian/Ubuntu
#    e dash e nao suporta [[ ]], arrays, etc).
#  - Alvos self-documented via comentarios `## <descricao>` na mesma linha —
#    parsed pelo alvo `help` via awk. Padrao consagrado em projetos DevOps
#    (ver: marmelab.com/blog/2016/02/29/auto-documented-makefile.html).
# =============================================================================

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

.PHONY: help check-env net up down restart ps logs clean setup

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

net:  ## Cria a rede docker compartilhada (idempotente)
	@if ! docker network inspect $(DOCKER_NETWORK) >/dev/null 2>&1; then \
		printf "$(CYAN)Criando rede docker '$(DOCKER_NETWORK)'...$(RESET)\n"; \
		docker network create $(DOCKER_NETWORK); \
	else \
		printf "$(GREEN)OK$(RESET) rede '$(DOCKER_NETWORK)' ja existe\n"; \
	fi

up: check-env net  ## Sobe infra + servicos (build se necessario)
	@printf "$(CYAN)=== infra ===$(RESET)\n"
	@docker compose --env-file $(ENV_FILE) -f $(INFRA_COMPOSE) up -d
	@printf "$(CYAN)Aguardando postgres healthy...$(RESET)\n"
	@for i in $$(seq 1 30); do \
		if docker inspect --format '{{.State.Health.Status}}' iot-postgres 2>/dev/null | grep -q healthy; then \
			printf "$(GREEN)OK$(RESET) postgres healthy\n"; break; \
		fi; sleep 2; \
	done
	@for f in $(SERVICE_COMPOSES); do \
		printf "$(CYAN)=== build+up: $$f ===$(RESET)\n"; \
		docker compose --env-file $(ENV_FILE) -f $$f up -d --build; \
	done
	@$(MAKE) --no-print-directory ps

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

setup:  ## (Fase 6) Bootstrap completo: infra + Keycloak + MinIO + migrations
	@printf "$(YELLOW)TODO:$(RESET) implementado na Fase 6.\n"
	@printf "Por enquanto rode: $(CYAN)make up$(RESET)\n"
