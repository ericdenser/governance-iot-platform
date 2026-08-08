# Governance IoT

  

### Projeto de Iniciação Tecnológica e Inovação — Universidade Presbiteriana Mackenzie (FCI-UPM) · Laboratório MackLeaps

  

Plataforma de governança e gerenciamento do ciclo de vida de dispositivos IoT, integrando conceitos de **MDM** (*Mobile Device Management*) e **CMDB** (*Configuration Management Database*) em uma arquitetura orientada a eventos.

  

> [!NOTE]
> Este projeto oferece implementações distintas que exploram diferentes paradigmas de arquiteturas de governança modernas. Cada abordagem é mantida em variante independente do código, permitindo comparação empírica entre latência de revoke, complexidade de firmware, footprint operacional e consumo de rede.

  

#### Arquiteturas de identidade

  

A gestão de identidade e os modelos de autenticação são pilares críticos na segurança de ambientes IoT. Para avaliar diferentes níveis de escalabilidade e delegação de confiança, a plataforma contempla 3 alternativas:

  
| Modelo | Autoridade | Autenticação | Revoke |
|---|---|---|---|
| **A — Centralizada mTLS** | MDM + Root CA própria | Certificado X.509 único assinado pela Root CA | CRL propagada ao broker |
| **B — Centralizada OAuth2** | MDM + Keycloak (IAM) | JWT access token assinado pelo Keycloak | Invalidação de token/client no IAM |
| **C — Descentralizada DID** | Blockchain | Par de chaves gerado no device com DID auto-emitido | Transação de revoke on-chain |


  

#### Protocolos de Comunicação

  

A camada de rede foi desenhada para suportar arquitetura baseada em Broker, utilizando Protocol Buffers (Protobuf) em ambas as abordagens para garantir máxima eficiência.

  

- **MQTT**: implementação focada no padrão de mercado para IoT, utilizando o broker Eclipse Mosquitto — amplamente testado e recomendado.

- **gRPC**: proposta em ascensão no cenário de IoT, focada em segurança e comunicação fortemente tipada, com broker open-source escrito em Rust para altíssima performance.

  

#### Implementações disponíveis

  

Onde encontrar cada uma no repositório:

  

| Abordagem | Protocolo | Broker | Path |
|---|---|---| --- |
| **A - Centralizada (mTLS)** | MQTT | Mosquitto | branch `main` | 
| **B - Centralizada (OAuth2)** | MQTT | Mosquitto |branch `alt/oauth2-jwt` | 
| **C - Descentralizada (DID)** | gRPC | Broker Rust | planejada | 

  

---

  

## Contexto

  

Com a expansão das *Smart Cities* e a previsão de mais de 29 bilhões de dispositivos conectados até 2030, a implantação em massa de microcontroladores de baixo custo expõe lacunas críticas de governança, segurança e escalabilidade. Frameworks tradicionais de TI não foram projetados para lidar com a heterogeneidade, a descentralização e as restrições de recursos desses dispositivos.

  

Este projeto responde diretamente à escassez de implementações práticas apontada por Sedrati et al. (2023) no framework IoT-Gov, concentrando esforços no terceiro pilar: **Gerenciamento de Dispositivos** — desde o provisionamento inicial até a revogação na rede.

 ![C4 Overview](docs/images/diagram/governance-iot-c4-overview-context.png) 

---

  

## O que a plataforma faz

  

Cinco capacidades cobertas por todas as implementações

  

- **Provisiona** microcontroladores de forma prática e automática.

- **Monitora** o estado da frota em tempo real via telemetria e eventos de interesse

- **Comanda** dispositivos remotamente: atualizações de firmware (OTA), firmware rollback, reboot, deep sleep

- **Revoga** acesso de dispositivos comprometidos

- **Registra** o inventário e o histórico de configurações no CMDB

  

---

  

## Arquitetura

  

O sistema segue o modelo de referência de Arquitetura IoT em Camadas (Umar et al., 2018; Gheorghe, 2025), estruturado em quatro *layers*:

![Architecture Diagram](docs/images/diagram/architecture-diagram.png)



---

  

## Estrutura do repositório

  

```

governance-iot/

├── perceptive-layer/    - Firmware ESP32-S3 (PROVISIONING + OPERATIONAL)

├── mosquitto/           - Broker MQTT

├── mbroker-main/        - Broker gRPC

├── service-layer/      

│ ├── governanceApi/     - API principal (MDM + CMDB)

│ ├── agents/agent-mqtt/ - Agent MQTT

│ ├── event-handler/     - Classificador de Eventos de Interesse

│ ├── datalogger/        - Persistência de telemetria (InfluxDB)

│ └── infra-executor/    - Executor de comandos de infra (exclusivo da impl A)

├── application-layer/

│ ├── bff/               - Backend for Frontend (sessão + OAuth2)

│ └── spa/               - Dashboard administrativo

├── docs/                - Documentação técnica

├── scripts/             - Scripts operacionais

└── docker-compose.yml   - Stack de infra (Postgres, Keycloak, Redis, MinIO, InfluxDB, Broker)

```

  

---

  

## Perceptive Layer

  

Camada conhecida por englobar a infraestrutura física da arquitetura IoT, como sensores e os dispositivos.

Neste projeto, foi utilizado o ESP32-S3 como microcontrolador com a responsabilidade de coletar os dados dos sensores, monitorar sua própria integridade via firmware e empacotar essas informações para envio à camada superior.

O repositório contém duas variantes de firmware para a *Perception Layer*, explorando diferentes fases do ciclo de vida do device.

  

| Firmware | Fase | Responsabilidade |
|---|---|---|
| `FIRM_PROVISIONING` | Provisionamento | Primeira comunicação com o sistema para introdução segura na frota |
| `FIRM_OPERATIONAL` | Operação | Telemetria, comandos remotos

  

---

  

## Network Layer

  

Responsável pela transmissão de informações entre o hardware físico e o serviço, atuando como um canal de transporte de dados. A comunicação é intermediada por um Broker, estruturado para fornecer confidencialidade e controle de acesso, garantindo que apenas dispositivos registrados e autorizados pela nossa API consigam publicar ou consumir informações.

  

O repositório mantém duas implementações paralelas de broker:

  

- **Eclipse Mosquitto 2.0** — padrão de mercado em IoT, ampla adoção comunitária

- **Broker gRPC (Rust)** — proposta em ascensão no cenário IoT, comunicação fortemente tipada e altíssima performance

  

---

  

## Service Layer

  

Esta camada é o cérebro da arquitetura, responsável por monitorar e gerenciar todo o ciclo de vida dos dispositivos de forma autônoma, desde o registro até sua remoção do sistema. A telemetria é capturada por agentes independentes inscritos nos tópicos do Broker, que repassam as mensagens para um serviço dedicado a persistir os dados no banco de séries temporais (InfluxDB). Paralelamente, um outro serviço classificador de Eventos de Interesse (EoI) monitora os eventos e aciona a API principal (govApi) apenas quando necessário. Esta API principal foca exclusivamente na orquestração do MDM, executando regras de negócio, enviando comandos diretos ao microcontrolador e persistindo o estado da frota no banco relacional (CMDB).

  

---

  

## Application Layer

  

A camada de abstração voltada para os operadores de TI e administradores do sistema. Contempla os dashboards de observabilidade e a interface administrativa responsável por interagir com a API Principal, permitindo o acionamento de rotinas do MDM (como revogar acesso ou disparar atualizações em lote).

  

---

  

## Como subir o seu govApi

  

> Pré-requisitos: Docker + Docker Compose, e uma máquina Linux com pelo menos 6 GB de RAM livre para toda a stack de infra + serviços.

  

```bash

git clone https://github.com/ericdenser/governance-iot-platform.git

cd governance-iot-platform

cp .env.example .env

# edite .env preenchendo secrets (POSTGRES_PASSWORD, KEYCLOAK_ADMIN_PASSWORD, MINIO_ACCESS_KEY, HOST_IP, …)

docker network create app-net # só na primeira vez

./scripts/up-all.sh

```

  

Ao final, o dashboard estará em `http://<HOST_IP>:8090`. Para reiniciar tudo sem rebuild: `./scripts/restart-all.sh`. Detalhes de troubleshooting e deploy em VM estão em [`docs/VM_SETUP.md`](docs/VM_SETUP.md).

  

---

  

## Documentação Técnica

  

Aprofundamentos técnicos organizados por tema em [`docs/`](docs/):

  

**Arquitetura e design**

- [Arquitetura geral](docs/ARCHITECTURE.md)

- [Modelo de domínio](docs/DOMAIN_MODEL.md)

- [Modelo de autorização](docs/AUTHORIZATION_MODEL.md)

- [Análise de segurança e escalabilidade](docs/SECURITY_SCALABILITY_ANALYSIS.md)

  

**Ciclo de vida do dispositivo**

- [Sequência de provisionamento](docs/PROVISIONING_SEQUENCE.md)

- [Ciclo de vida do firmware](docs/FIRMWARE_LIFECYCLE.md)

- [Tuning de rollout OTA](docs/OTA_ROLLOUT_TUNING.md)

- [Cenários de rollback OTA](docs/OTA_ROLLBACK_SCENARIOS.md)

  

**Implementações específicas**

- [Alt A - mTLS ](docs/mtls-docs/README.md)

- [Alt B - OAuth2/JWT ](docs/oauth-docs/README.md)

- [Alt C - DID](docs/did-docs.md)

  

**Operacional**

- [Setup em VM](docs/VM_SETUP.md)

- [Contrato Redis Streams](docs/REDIS_STREAMS_CONTRACT.md)

  

---

  

## Licença

  

Projeto acadêmico desenvolvido no âmbito da Iniciação Tecnológica FCI-UPM.

Atribuições de terceiros em [`LICENSES/`](LICENSES/).
  

---

  

## Instituição

  

Projeto desenvolvido no laboratório **MackLeaps** da Faculdade de Computação e Informática da Universidade Presbiteriana Mackenzie (FCI-UPM)
