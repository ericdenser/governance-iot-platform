# governance_core

Componente ESP-IDF que implementa a infraestrutura MDM (MQTT + OTA + provisioning + boot audit) do Governance IoT. Seu firmware só precisa implementar leitura de sensores; toda a state machine, WiFi, protocolo com o broker, OTA e error handling vem prontos.

---

## Quick start (5 min)

### 1. Adiciona o componente ao seu projeto

No `CMakeLists.txt` da raiz do projeto:

```cmake
set(EXTRA_COMPONENT_DIRS "path/to/governance_core")
```

No `main/CMakeLists.txt`:

```cmake
idf_component_register(
    SRCS "main.cpp"
    INCLUDE_DIRS "."
    REQUIRES
        governance_core
        driver          # se seus sensores precisam de GPIO/I2C/UART
        esp_adc         # se ler bateria por ADC
)
```

### 2. Mínimo funcional — `main.cpp`

```cpp
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "governance_core.h"

extern "C" void app_main(void) {
    governance_hooks_t hooks = {};  // sem sensores, sem time sync
    governance_core_init(&hooks);
}
```

Isso já **conecta ao WiFi, faz provisioning, mantém sessão MQTT, aceita comandos OTA/reboot/deep_sleep**. Sem publicar telemetria (nenhum sensor).

Configura MQTT broker + credenciais via `idf.py menuconfig` → **Governance IoT** e **WiFi Manager Configuration**.

### 3. Configura o Kconfig

`idf.py menuconfig` → **Governance IoT**:
- `GOV_MQTT_BROKER_URI` — obrigatório
- `GOV_TELEMETRY_INTERVAL_MS`, `GOV_STATUS_INTERVAL_MS` — padrão OK
- `GOV_MAX_WIFI_RETRIES`, `GOV_MAX_CRASH_COUNT` — padrão OK

`idf.py menuconfig` → **WiFi Manager Configuration**:
- SSID / password ou usa NVS pré-populada pelo flash package do MDM

---

## Hooks — o que você implementa

Todos os hooks são opcionais. Se você não seta, o core assume comportamento default (geralmente "não fazer nada"). Setar só os que fazem sentido pro seu hardware.

### Hook 1 — `sensor_discovery` (recomendado)

**Quando é chamado:** uma vez, ao entrar no estado `SENSORS_INIT` (depois de MQTT conectar e boot audit).

**O que retornar:** string CSV com os nomes dos sensores detectados. Ex: `"bmp280,gps,battery"`. Vai aparecer no campo `active_sensors` do `DeviceStatus` publicado periodicamente.

**Uso típico:**

```cpp
static std::string g_activeSensorsCSV;

static const char* my_sensor_discovery(void) {
    g_activeSensorsCSV.clear();

    if (MySensorX::probe()) g_activeSensorsCSV += "sensor_x";
    // ... outros probes ...

    return g_activeSensorsCSV.c_str();
}
```

**Se você não setar:** o CSV fica vazio e telemetria não publica nenhuma leitura (sem read_telemetry também).

---

### Hook 2 — `read_telemetry` (recomendado)

**Quando é chamado:** a cada `GOV_TELEMETRY_INTERVAL_MS` (padrão 5s), enquanto estado for `OPERATIONAL`.

**Assinatura:**
```cpp
int my_read_telemetry(sensor_reading_t* out, int max);
```

**O que fazer:**
- Ler seus sensores
- Preencher `out[i].key` (nome do canal, ex `"temperature"`, `"co2_ppm"`) e `out[i].value` (float)
- Retornar quantas leituras preencheu (0 = nada pra publicar)
- **Não exceder `max`** (é `GOVERNANCE_MAX_READINGS`, padrão 8)

**Exemplo:**

```cpp
static int my_read_telemetry(sensor_reading_t* out, int max) {
    int n = 0;
    if (g_has_bmp280 && n + 2 <= max) {
        strncpy(out[n].key, "temperature", sizeof(out[n].key) - 1);
        out[n].value = bmp280_read_temp(); n++;
        strncpy(out[n].key, "humidity", sizeof(out[n].key) - 1);
        out[n].value = bmp280_read_hum();  n++;
    }
    // ... mais leituras condicionalmente ...
    return n;
}
```

**Payload publicado:** `DeviceTelemetry` protobuf no tópico `telemetry/{device_id}` a cada intervalo.

---

### Hook 3 — `get_persisted_time` (opcional, RTC persistente)

**Quando é chamado:** no início do estado `TIME_SYNC` (após conectar WiFi, antes de conectar MQTT).

**O que retornar:** `time_t` (Unix epoch em segundos) se o RTC persistente (DS3231, PCF8563, ou similar) tem hora válida. **0** se sem hardware ou sem hora sincronizada previamente.

**Se você tem DS3231:**

```cpp
static time_t my_get_persisted_time(void) {
    if (!my_ds3231_probe()) return 0;
    struct tm ti = {};
    if (!my_ds3231_read(&ti) || ti.tm_year < 124) return 0;
    return mktime(&ti);
}
```

**Se você não tem:** não seta o hook. Core pula direto pro external time.

---

### Hook 4 — `persist_time` (opcional, grava no RTC)

**Quando é chamado:** depois que `get_external_time` retornar hora válida — pra gravar no RTC persistente e sobreviver a reboots.

**Só setar se você tem RTC gravável.**

```cpp
static void my_persist_time(time_t epoch) {
    struct tm ti;
    gmtime_r(&epoch, &ti);
    my_ds3231_write(&ti);
}
```

**Combinado com `get_persisted_time`**, você tem o padrão "RTC como primário, fonte externa refresca periodicamente" — que é o mais robusto pra devices em campo.

---

### Hook 5 — `get_external_time` (opcional, GPS/NTP)

**Quando é chamado:**
- Se `get_persisted_time` retornou 0 (sem hora persistida)
- Ou a cada `GOV_GPS_SYNC_EVERY_N_BOOTS` boots (refresh periódico do RTC)

**O que retornar:** `time_t` do sensor externo (GPS, NTP), ou **0** se timeout/falha.

**Você recebe:** `timeout_ms` — quantos ms você tem pra tentar obter fix.

```cpp
static time_t my_get_external_time(uint32_t timeout_ms) {
    // Liga GPS, aguarda fix, lê hora
    GpsFix fix = {};
    if (!GpsManager::waitForFix(TX, RX, PORT, fix, timeout_ms)) return 0;
    return mktime(&fix.time);
}
```

**Se você não tem fonte externa:** não seta. Core usa só `get_persisted_time`.

---

### Hook 6 — `get_battery_mv` (opcional, futuro low-battery event)

**Assinatura:** retorna nível de bateria em mV (uint32_t). 0 se sem hardware.

Ainda não é usado pelo core (versão atual do componente). Está reservado pra evento futuro `DEVICE_LOW_BATTERY`.

---

## Fluxo completo — exemplo real com sensor CO2 custom

Cenário: seu firmware tem um sensor MH-Z19B (CO2 via UART) que **você mesmo escreveu o driver**. Nenhum sensor conhecido do core (sem GPS, sem DS3231). Você quer publicar `co2_ppm` a cada 5 segundos.

### `main/src/CO2Manager.h`

```cpp
#pragma once
class CO2Manager {
public:
    static bool probe();       // detecta hardware
    static float readPPM();    // leitura atual
};
```

### `main/src/CO2Manager.cpp`

```cpp
#include "CO2Manager.h"
#include "driver/uart.h"

bool CO2Manager::probe() {
    // init UART, envia comando de handshake, verifica resposta
    return true; // se detectou
}

float CO2Manager::readPPM() {
    // envia comando de leitura, parseia resposta
    return 420.0f;
}
```

### `main/main.cpp`

```cpp
#include <string>
#include "freertos/FreeRTOS.h"
#include "governance_core.h"
#include "src/CO2Manager.h"

static bool g_has_co2 = false;
static std::string g_csv;

static const char* my_sensor_discovery(void) {
    g_csv.clear();
    g_has_co2 = CO2Manager::probe();
    if (g_has_co2) g_csv = "co2";
    return g_csv.c_str();
}

static int my_read_telemetry(sensor_reading_t* out, int max) {
    if (!g_has_co2 || max < 1) return 0;
    strncpy(out[0].key, "co2_ppm", sizeof(out[0].key) - 1);
    out[0].value = CO2Manager::readPPM();
    return 1;
}

extern "C" void app_main(void) {
    governance_hooks_t hooks = {};
    hooks.sensor_discovery = my_sensor_discovery;
    hooks.read_telemetry   = my_read_telemetry;
    governance_core_init(&hooks);
}
```

### `main/CMakeLists.txt`

```cmake
idf_component_register(
    SRCS
        "main.cpp"
        "src/CO2Manager.cpp"
    INCLUDE_DIRS "." "src"
    REQUIRES
        governance_core
        driver
)
```

**Isso é tudo.** Publica `co2_ppm` e esta 100% integrado no sistema.

---

## GOVERNANCE_CORE includes:

- ✅ Boot state machine (`NVS_INIT → WIFI_CONNECTING → TIME_SYNC → MQTT_INIT → BOOT_AUDIT → SENSORS_INIT → OPERATIONAL`)
- ✅ WiFi (retries + reconnection + fallback SSID)
- ✅ mTLS with broker MQTT (device cert via NVS, root CA on /cert )
- ✅ Provisioning via HTTP (only on provisioning firmware, operational skips)
- ✅ OTA end-to-end (download HTTPS + verify signature + rollback in N crashes)
- ✅ Status and Errors publish using MQTT with protobuf
- ✅ Command receiver (UPDATE, REBOOT, DEEP_SLEEP, FIRMWARE_ROLLBACK)
- ✅ Boot audit (detect OTA, rollback, crash, command complete after reboot)
- ✅ Watchdog on principal loop + reset each iteration
- ✅ MQTT Message queue (dedicated task, no race in publish)

---

## Kconfig (menuconfig)

Todas em **Governance IoT**:

| Config | Uso | Default |
|---|---|---|
| `GOV_MQTT_BROKER_URI` | URI do broker (mqtts:// ou wss://) | `mqtts://xxx.xxx.xx.xx:8883` |
| `GOV_TELEMETRY_INTERVAL_MS` | Intervalo publish telemetria | 5000 |
| `GOV_STATUS_INTERVAL_MS` | Intervalo publish status | 30000 |
| `GOV_MAX_WIFI_RETRIES` | Tentativas WiFi antes de erro | 10 |
| `GOV_MAX_CRASH_COUNT` | Crashes até invalidar firmware | 3 |
| `GOV_GPS_SYNC_EVERY_N_BOOTS` | Ciclo de refresh via external_time | 24 |
| `GOV_GPS_POWER_PIN` | GPIO alimentação GPS (só user code usa) | 5 |
| `GOV_GPS_UART_TX_PIN` | GPIO TX UART GPS (só user code usa) | 17 |
| `GOV_GPS_UART_RX_PIN` | GPIO RX UART GPS (só user code usa) | 18 |
| `GOV_GPS_UART_PORT` | Porta UART GPS (só user code usa) | 1 |

Os últimos 4 (pinos de GPS) são convenção — só relevantes se seu firmware usa GPS, mas expostos no menu do core pra centralizar.

---

## Cheatsheet

**"Quero adicionar sensor X"** → implementa `probe()` e `read()` no seu driver, integra nos 2 hooks obrigatórios (discovery + telemetry).

**"Meu device tem DS3231 mas não GPS"** → seta `get_persisted_time` e `persist_time`. Sem `get_external_time`. Core só usa DS3231.

**"Meu device tem GPS mas não RTC"** → seta `get_external_time`. Sem os outros dois. Core busca hora do GPS a cada boot.

**"Quero receber comando MQTT custom"** → não implementado nesta versão. Escopo futuro (hook `handle_custom_command`).

**"Quero mudar como o publish funciona"** → não é possível sem forkar o core. O contrato do MQTT (protobuf schemas, tópicos, QoS) é responsabilidade da plataforma, não do firmware.

---

## Estrutura de um projeto novo

```
meu_firmware/
├── CMakeLists.txt              (aponta EXTRA_COMPONENT_DIRS pro governance_core)
├── sdkconfig.defaults          (defaults do menuconfig, checked-in)
├── partitions.csv              (partições OTA)
└── main/
    ├── CMakeLists.txt          (REQUIRES governance_core + suas deps)
    ├── main.cpp                (app_main + hooks)
    └── src/
        └── MeuSensor.cpp/.h    (drivers específicos)
```

Não precisa nem `Kconfig.projbuild` no seu `main/` — o menu já vem do componente.
