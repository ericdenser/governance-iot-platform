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

1. No Boards Manager, instale **esp8266 by ESP8266 Community 3.1.2**. Selecione a placa *NodeMCU 1.0 (ESP-12E Module)* e configure **Flash Size: 4MB (FS:2MB OTA:~1019KB)**. Essa configuração deve ser mantida em todas as versões.
2. No Library Manager, instale **PubSubClient 2.8**, de Nick O'Leary.
3. Configure em `config.h` as URLs do govApi e do Keycloak, o host do broker e os intervalos.
4. Gere o binário em *Sketch -> Export compiled Binary* ou execute:

```bash
arduino-cli compile \
  --fqbn esp8266:esp8266:nodemcuv2:eesz=4M2M \
  --export-binaries FIRM_ESP8266_MQTT_PROTOBUFF
```

O mesmo `.bin` pode ser usado em todos os dispositivos. Os dados específicos de cada um são fornecidos pelo arquivo `/prov.txt`.

## Provisioning via LittleFS

O arquivo `/prov.txt` contém os dados usados pelo govApi, com as mesmas chaves do NVS do ESP32. Cada linha segue o formato `chave=valor`, sem aspas e em UTF-8. O valor pode conter `=`.

```text
wifi_ssid=...
wifi_pass=...
prov_token=...
device_id=...
fw_version=...
```

No boot, se não houver credenciais salvas e existir `/prov.txt`, o firmware conecta ao Wi-Fi e chama `/provisioning/activate`. Se a ativação funcionar, salva as credenciais em `/creds.txt` (`client_id`, `client_secret` e `device_id`), grava os dados de Wi-Fi em `/wifi.txt` e apaga `/prov.txt`, pois o token é de uso único.

O valor de `fw_version` é usado até que uma OTA atualize a versão registrada em `/fw.txt`.

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

### Gravar na placa

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
  -o littlefs.bin
```

Grave o firmware e a imagem:

```bash
python3 -m esptool --chip esp8266 --port /dev/ttyUSB0 \
  --baud 460800 write_flash \
  0x0 FIRM_ESP8266_MQTT_PROTOBUFF.ino.nodemcu.bin \
  0x200000 littlefs.bin
```

O binário gerado pelo Arduino já inclui o bootloader (eboot). Não é necessário gravar arquivos separados de bootloader ou tabela de partições.

Para reprovisionar um dispositivo, gere um novo token e grave outra imagem LittleFS. Isso substitui os arquivos `/creds.txt` e `/wifi.txt`.

 ## OTA

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
