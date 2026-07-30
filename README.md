# Governance IoT

> Projeto de Iniciação Tecnológica e Inovação — Universidade Presbiteriana Mackenzie (FCI-UPM) · Laboratório MackLeaps

Plataforma de governança e gerenciamento do ciclo de vida de dispositivos IoT, integrando conceitos de **MDM** (*Mobile Device Management*) e **CMDB** (*Configuration Management Database*) em uma arquitetura orientada a eventos.

> [!NOTE]
> Este projeto oferece implementações distintas que exploram diferentes paradigmas de arquiteturas de governança modernas. Cada abordagem é mantida em variante independente do código, permitindo comparação empírica entre latência de revoke, complexidade de firmware, footprint operacional e consumo de rede.

#### Arquiteturas de identidade
A gestão de identidade e modelos de autenticação são pilares críticos na segurança de ambientes IoT. Para avaliar diferentes níveis de escalabilidade e delegação de confiança, a plataforma foi projetada de forma flexível, contemplando 3 alternativas distintas:
- **Identidade Centralizada A**: Baseada em autenticação mTLS, com o MDM atuando como autoridade central. Neste modelo, a identidade do dispositivo é garantida por um UUID atribuído pelo MDM, que vira o CN do certificado X.509 assinado e emitido pela Root CA da autoridade. Este certificado é usado posteriormente para a autenticação do dispositivo no broker.

- **Identidade Centralizada B**: Baseada em autenticação OAuth2, com o MDM atuando como autoridade central e Keycloak como IAM. Neste modelo, a identidade do dispositivo é garantida por um UUID atribuído pelo MDM, que vira o client_id do client do Keycloak. O dispositivo recebe um client-secret, utilizado para requisitar os access tokens usados posteriormente para autenticação no broker.

- **Identidade Descentralizada**: Utiliza uma rede blockchain local como a única fonte de verdade para os registros da frota. Cada dispositivo gera seu próprio par de chaves criptográficas, a partir do qual é derivado um Identificador Descentralizado (DID) exclusivo, sendo posteriormente registrado e validado de forma imutável na blockchain. Neste modelo, o acesso ao broker é aberto, com a autenticação ocorrendo no Agent.

#### Protocolos de Comunicação

A camada de rede foi desenhada para suportar uma arquitetura baseada em Broker, utilizando Protocol Buffers (Protobuf) em ambas as abordagens para garantir máxima eficiência.
- **MQTT**: Implementação focada no padrão de mercado para IoT. Utiliza o broker Eclipse Mosquitto amplamente testado e recomendado.

- **gRPC**: Uma nova proposta em ascensão no cenário de IoT, focada em segurança e comunicação fortemente tipada. Utiliza um broker gRPC open-source desenvolvido inteiramente em Rust, de altíssima performance.

---

## Contexto

Com a expansão das *Smart Cities* e a previsão de mais de 29 bilhões de dispositivos conectados até 2030, a implantação em massa de microcontroladores de baixo custo expõe lacunas críticas de governança, segurança e escalabilidade. Frameworks tradicionais de TI não foram projetados para lidar com a heterogeneidade, a descentralização e as restrições de recursos desses dispositivos.

Este projeto responde diretamente à escassez de implementações práticas apontada por Sedrati et al. (2023) no framework IoT-Gov, concentrando esforços no terceiro pilar: **Gerenciamento de Dispositivos** — desde o provisionamento inicial até a revogação na rede.

---

## O que a plataforma faz

- **Provisiona** microcontroladores remotamente, emitindo uma identidade criptográfica X.509 única gerada no próprio hardware (chave privada ECC nunca sai do device)
- **Monitora** o estado da frota em tempo real via telemetria e eventos de interesse
- **Comanda** dispositivos remotamente: atualizações de firmware (OTA), reboot, deep sleep, entre outros
- **Revoga** acesso de dispositivos comprometidos via CRL, bloqueando conexões no broker imediatamente
- **Registra** o inventário e o histórico de configurações no CMDB (PostgreSQL)

---

## Arquitetura 

O sistema segue o modelo de referência de Arquitetura IoT em Camadas (Umar et al., 2018; Gheorghe, 2025), estruturado em quatro *layers*:

![Diagrama de Arquitetura do Projeto](imagem.png)

---

## Perceptive-Layer

Camada conhecida por englobar a infraestrutura física da arquitetura
IoT, como sensores e os dispositivos. 

Neste projeto, foi utilizado o ESP32-S3 como
microcontrolador com a responsabilidade de coletar os dados dos sensores, monitorar sua própria integridade via
firmware e empacotar essas informações para envio à camada superior.

O repositório contém duas variantes de firmware para a *Perception Layer*, explorando diferentes fases do ciclo de vida do device.

| Firmware | Fase | Responsabilidade |
|---|---|---|
| `FIRM_PROVISIONING` | Provisionamento | Geração de credenciais no hardware, primeira comunicação com o backend |
| `FIRM_OPERATIONAL` | Operação | Telemetria, comandos remotos, OTA, deep sleep |
---

## Network Layer

Responsável pela transmissão de informações entre o hardware físico e
o serviço, atuando como um canal de transporte de dados. Adotaremos duas implementações de protocolo. 
A comunicação será intermediada por um Broker,
estruturado para fornecer confidencialidade e controle de acesso, garantindo que apenas
dispositivos registrados e autorizados pela nossa API consigam publicar ou consumir
informações.
O repositório mantém duas implementações paralelas de broker:

- **Eclipse Mosquitto 2.0** — padrão de mercado em IoT, ampla adoção comunitária 
- **Broker gRPC (Rust)** — proposta em ascensão no cenário IoT, comunicação fortemente tipada e altíssima performance

---
## Service-Layer
 Esta camada é o cérebro da arquitetura, responsável por monitorar e
gerenciar todo o ciclo de vida dos dispositivos de forma autônoma, desde o registro até sua
remoção do sistema. A telemetria é capturada por agentes independentes inscritos nos tópicos do Broker, que repassam as mensagens para um serviço dedicado a
persistir os dados no banco de séries temporais (InfluxDB). Paralelamente, um outro serviço
classificador de Eventos de Interesse (EoI) monitora os eventos e aciona a API principal (govApi)
apenas quando necessário. Esta API principal foca exclusivamente na orquestração do
MDM, executando regras de negócio, enviando comandos diretos ao microcontrolador e
persistindo o estado da frota no banco relacional (CMDB).





---

## Application-Layer
A camada de abstração voltada para os operadores de TI e administradores do sistema. É onde os dados processados se transformam em ações. Contempla os dashboards de observabilidade e a interface administrativa responsável por interagir com a API Principal, permitindo o acionamento de rotinas do MDM (como revogar acesso ou disparar atualizações em lote).


---


## Documentação e Detalhes Técnicos

Para manter este guia conciso, todos os aprofundamentos técnicos, comparações de arquitetura de protocolos (MQTT vs gRPC), stack de tecnologias utilizadas e manuais de execução foram separados na nossa documentação oficial.

Consulte a pasta [`docs/`](docs/) para acessar:
- [Comparativo e Implementação de Protocolos (MQTT vs gRPC)](docs/protocolos.md)
- [Stack de Tecnologias (Firmware, Serviços e Infraestrutura)](docs/tecnologias.md)
- [Fluxo de Provisionamento e Estrutura NVS](docs/provisionamento.md)
- [Arquitetura dos Microsserviços e Endpoints](docs/microsservicos.md)
- [Instruções de Instalação e Execução](docs/setup.md)

---

## Instituição

Projeto desenvolvido no laboratório **MackLeaps** da Faculdade de Computação e Informática da Universidade Presbiteriana Mackenzie (FCI-UPM)

