#!/bin/bash

echo "Carregando variaveis do .env..."

set -a
source /home/eric/WorkSpace/IT/GOVERNANCE_IOT/.env
set +a

echo "Variaveis carregadas! Iniciando o BFF..."

mvn spring-boot:run
