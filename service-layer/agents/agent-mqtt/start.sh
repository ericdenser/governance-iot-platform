#!/bin/bash

echo "Carregando variaveis do .env..."

# 1. Ativa a exportação automática de variáveis
set -a

# 2. Lê o arquivo .env que está 3 pastas acima
source /home/eric/WorkSpace/IT/GOVERNANCE_IOT/.env

# 3. Desativa a exportação automática
set +a

echo "Variaveis carregadas! Iniciando o Governance API..."

# 4. Roda o Spring Boot (Tem que estar AQUI DENTRO!)
mvn spring-boot:run