# FIRM_ESP8266_MQTT_PROTOBUFF

Firmware para ESP8266. Implementa a variante do oauth2 com Keycloak, e mantém protobuff.

Fluxo de execução:

`NVS_INIT -> WIFI_CONNECTING -> TIME_SYNC -> [PROVISIONING] -> MQTT_WAITING_CONNECT -> OPERATIONAL`

Fluxo de OTA:

`OPERATIONAL -> OTA_FOUND -> OTA_DOWNLOADING reboot -> OTA_SUCCESSFUL -> OPERATIONAL`

## Tópicos MQTT

| Tópico | Conteúdo | Intervalo |
|---|---|---|
| `status/{device_id}` | `PROVISIONING_SUCCESS` (uma vez após o provisioning) e depois `OPERATIONAL` | `GOV_STATUS_INTERVAL_MS` |
| `telemetry/{device_id}` | `rssi`, `free_heap` | `GOV_TELEMETRY_INTERVAL_MS` |
| `status/{device_id}` | `OTA_SUCCESSFUL` (uma vez após o reboot da OTA) | — |
| `error/{device_id}` | `OTA_FAIL`, com `attempted_version` e `attempted_sha` | — |
| `commands/{device_id}/#` (subscrição) | Comandos `UPDATE` | — |

Não implementados: `REBOOT`, `DEEP_SLEEP`, `FIRMWARE_ROLLBACK` e deep sleep.

## Build

### Arduino IDE

1. Em *Preferências → URLs adicionais de gerenciadores de placas*, adicione `https://arduino.esp8266.com/stable/package_esp8266com_index.json`.
2. No Boards Manager, instale **esp8266 by ESP8266 Community 3.1.2**. Selecione a placa *NodeMCU 1.0 (ESP-12E Module)* e configure **Flash Size: 4MB (FS:2MB OTA:~1019KB)**. Essa configuração deve ser mantida em todas as versões.
3. No Library Manager, instale **PubSubClient 2.8**, de Nick O'Leary.
4. Configure o `config.h` (ver abaixo).
5. *Sketch → Export compiled Binary*. O `.bin` fica na pasta do sketch.

### arduino-cli

```bash
arduino-cli config init
arduino-cli config add board_manager.additional_urls https://arduino.esp8266.com/stable/package_esp8266com_index.json
arduino-cli core update-index
arduino-cli core install esp8266:esp8266@3.1.2
arduino-cli lib install "PubSubClient@2.8"

# na pasta mqtt_protobuff/
arduino-cli compile \
  --fqbn esp8266:esp8266:nodemcuv2:eesz=4M2M \
  --export-binaries FIRM_ESP8266_MQTT_PROTOBUFF
```

O binário fica em `FIRM_ESP8266_MQTT_PROTOBUFF/build/esp8266.esp8266.nodemcuv2/FIRM_ESP8266_MQTT_PROTOBUFF.ino.bin`. Ele precisa caber no slot de OTA (~1019 KB).

### config.h

Só endereços da plataforma. Use o `HOST_IP` do `.env` da stack:

```c
#define GOV_MDM_BASE_URL   "http://<HOST_IP>:8082"
#define GOV_KEYCLOAK_URL   "http://<HOST_IP>:8080"
#define GOV_KEYCLOAK_REALM "IOT-REALM"
#define GOV_MQTT_HOST      "<HOST_IP>"
#define GOV_MQTT_PORT      1883
```

O mesmo `.bin` pode ser usado em todos os dispositivos. Os dados específicos de cada um são fornecidos pelo arquivo `/prov.txt`.

## Provisionar pela plataforma

No ESP8266 não existe firmware separado de provisioning: o mesmo sketch se provisiona sozinho. Por isso o `.bin` é cadastrado duas vezes no SPA.

1. **Cadastrar o firmware de provisioning** (uma vez por versão): em *Firmware*, selecione o chip **ESP8266**, clique em *Novo Firmware*, envie o `.bin` e marque *Firmware de provisionamento*. A versão informada é a que o device reporta até a primeira OTA.
2. **Gerar o pacote**: ainda com **ESP8266** selecionado, clique em *Provisionar*. Informe o nome do device, a rede WiFi (obrigatória, 2.4 GHz) e o grupo. O SPA baixa um ZIP com o `.bin`, o `littlefs.bin` (com o `/prov.txt`) e um README com o comando de gravação.
3. **Gravar** (porta em `/dev/ttyUSB0`; o usuário precisa estar no grupo `dialout`):

   ```bash
   python3 -m esptool --chip esp8266 --port /dev/ttyUSB0 --baud 460800 write_flash \
     0x0 <firmware>.bin \
     0x200000 littlefs.bin
   ```

4. **Acompanhar**: o device aparece como `PENDING`, passa a `PROVISIONING` quando chama o `/provisioning/activate` e vira `ACTIVE` ao publicar o primeiro status.

O token do pacote é de uso único e expira. Se a gravação atrasar ou falhar depois da ativação, gere um pacote novo.

## OTA pela plataforma

1. Cadastre um firmware **operational** para ESP8266 (*Novo Firmware*, sem marcar provisioning) e suba as versões nele.
2. Na versão desejada, *Deploy* → selecione os devices ESP8266. O SPA só lista devices do mesmo chip e, para ESP8266, só libera o comando `UPDATE`.

## Monitorar

```bash
arduino-cli monitor -p /dev/ttyUSB0 -c baudrate=115200
# ou
python3 -m serial.tools.miniterm /dev/ttyUSB0 115200
```

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| Log diz que não há credenciais nem `/prov.txt` | `littlefs.bin` não gravado em `0x200000`, ou sketch compilado com outro Flash Size |
| `/provisioning/activate` responde 4xx | Token já usado ou expirado: gere outro pacote |
| Conecta no WiFi mas não no MQTT | `GOV_MQTT_HOST` errado, porta 1883 bloqueada ou Keycloak inacessível (o JWT vai no CONNECT) |
| Status com horário errado logo após o boot | NTP ainda sincronizando; corrige sozinho |

## Provisioning via LittleFS (detalhes)

O arquivo `/prov.txt` contém os dados usados pelo govApi, com as mesmas chaves do NVS do ESP32. Cada linha segue o formato `chave=valor`, sem aspas e em UTF-8. O valor pode conter `=`.

```text
wifi_ssid=...
wifi_pass=...
prov_token=...
device_id=...
fw_version=...
fw_sha=...
```

No boot, se não houver credenciais salvas e existir `/prov.txt`, o firmware conecta ao Wi-Fi e chama `/provisioning/activate`. Se a ativação funcionar, salva as credenciais em `/creds.txt` (`client_id`, `client_secret` e `device_id`), grava os dados de Wi-Fi em `/wifi.txt` e apaga `/prov.txt`, pois o token é de uso único.

Os valores de `fw_version` e `fw_sha` são usados até que uma OTA atualize a versão registrada em `/fw.txt`. Com o `fw_sha`, um UPDATE para o mesmo binário é recusado antes do download, com a mesma mensagem do ESP32 (`Stopping OTA download due same firmware SHA256`). Sem ele, o device baixa, reinicia e só então percebe que o sketch não mudou.

Se não houver credenciais nem `/prov.txt`, o dispositivo entra em `ERROR`, registra mensagens no log e reinicia após `GOV_BOOT_TIMEOUT_S`.

### Configuração da imagem LittleFS

Para o core ESP8266 3.1.2, usando `eesz=4M2M`:

| Parâmetro | Valor | Origem |
|---|---|---|
| Offset | `0x200000` | `spiffs_start` em `boards.txt` |
| Tamanho | `0x1FA000` (2.072.576 bytes) | `spiffs_end - spiffs_start` |
| Bloco | `8192` | `spiffs_blocksize` |
| Página | `256` | `spiffs_pagesize`, usado pelo `mklittlefs` |
| Read/prog/cache/lookahead | `64` | `LittleFS.h` |
| `block_cycles` | `16` | `LittleFS.h` |
| `name_max` | `32` | `LFS_NAME_MAX` em `LittleFS.h` |
| `disk_version` | `0x00020000` (v2.0) | `lfs.h` 2.5 do core |

Os parâmetros `name_max` e `disk_version` precisam corresponder aos usados pelo core. Os valores padrão do `littlefs-python` (255 e v2.1) são incompatíveis: o LittleFS não monta, e o firmware pode formatar a flash, apagando o `/prov.txt`.

O script `tools/gen_prov_littlefs.py` gera a imagem com esses parâmetros e foi validado com o `mklittlefs` do core, nos dois sentidos.

### Gerar e gravar manualmente

Sem passar pelo SPA (o token ainda vem do govApi):

Instale as dependências:

```bash
pip install littlefs-python esptool
```

Gere a imagem LittleFS:

```bash
python3 tools/gen_prov_littlefs.py \
  --ssid REDE \
  --password SENHA \
  --token TOKEN \
  --device-id UUID \
  --fw-version 1.0.0 \
  --fw-sha SHA256_DO_BIN \
  -o littlefs.bin
```

Grave o firmware e a imagem:

```bash
python3 -m esptool --chip esp8266 --port /dev/ttyUSB0 \
  --baud 460800 write_flash \
  0x0 FIRM_ESP8266_MQTT_PROTOBUFF.ino.bin \
  0x200000 littlefs.bin
```

O binário gerado pelo Arduino já inclui o bootloader (eboot). Não é necessário gravar arquivos separados de bootloader ou tabela de partições.

Para reprovisionar um dispositivo, gere um novo token e grave outra imagem LittleFS. Isso substitui os arquivos `/creds.txt` e `/wifi.txt`.

## OTA (detalhes)

Arquivos usados no processo:

- `/fw.txt`: versão, SHA e MD5 do sketch.
- `/ota_pending.txt`: estado da atualização pendente.
- `/ota_announce`: dados da atualização a anunciar.
- `/ota_fail.txt`: informações sobre falhas.

**A OTA não implementa rollback.** A imagem anterior não é preservada como opção de recuperação após a atualização. Se o novo firmware travar em um loop, será necessário gravar outro firmware pela serial.

O SHA-256 recebido no comando identifica a versão, mas o conteúdo baixado não é validado contra esse hash. A implementação de `ESP8266httpUpdate` usada aqui não disponibiliza o stream para calcular o SHA-256 durante o download.

## Diferenças em relação ao ESP32

- **Armazenamento:** o ESP8266 usa LittleFS com `/prov.txt`, em vez de uma imagem `nvs_device.bin`.
- **Configuração da flash:** mudar o Flash Size entre versões altera a posição do LittleFS e pode invalidar as credenciais.
- **Provisioning:** ocorre no próprio firmware de operação. No ESP32, o fluxo usa dois firmwares e OTA.
- **MQTT:** conexão TCP sem TLS na porta 1883. O PubSubClient não oferece suporte a WebSockets (`wss://`).
- **QoS:** publicações em QoS 0, limitação do PubSubClient.
- **HTTPS:** o govApi e o Keycloak aceitam URLs `https://`, mas a conexão usa `setInsecure()`, sem validar o certificado do servidor.
