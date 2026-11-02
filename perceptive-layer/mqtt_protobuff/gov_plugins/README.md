# Plugins de sensor

O `governance_core` funciona como um microkernel: ele não conhece nenhum sensor. Cada sensor é um componente ESP-IDF nesta pasta e se registra no kernel por uma interface única (`governance_core/include/gov_sensor.h`).

O kernel cuida de:
- **Descoberta:** chama `probe()` e `init()` de cada plugin no `SENSORS_INIT` e monta a lista de sensores ativos enviada à plataforma.
- **Amostragem:** uma task chama `read()` no período de cada plugin e guarda o último valor.
- **Telemetria:** junta os valores recentes de todos os plugins (mais o `rssi`) e publica.
- **Leitura para outras partes do firmware:** `gov_sensor_get(code, metric, ...)`, usada pela tela do LCD, por exemplo.

| Componente | Code | Métricas |
|---|---|---|
| `gov_hal` | — | barramentos compartilhados (I2C e ADC) |
| `gov_sensor_bmp280` | `bmp280` | temperature, pressure (hPa), humidity (BME280) |
| `gov_sensor_mq135` | `mq135` | co2_ppm (depois do aquecimento) |
| `gov_sensor_gps` | `gps` | latitude, longitude, altitude (com fix) |
| `gov_sensor_ds3231` | `ds3231` | nenhuma (RTC; hora entre boots) |
| `gov_sensor_battery` | `battery_adc` | battery_mv |
| `gov_sensor_chip_temp` | `chip_temp` | temp_c |

## Adicionar um sensor

Exemplo: um AHT25 (I2C, temperatura e umidade).

### 1. Criar o componente `gov_plugins/gov_sensor_aht25/`

```
gov_sensor_aht25/
├── CMakeLists.txt
├── Kconfig.projbuild
├── idf_component.yml        (só se usar biblioteca do registry)
├── include/gov_sensor_aht25.h
└── src/gov_sensor_aht25.cpp
```

`CMakeLists.txt`
```cmake
idf_component_register(
    SRCS "src/gov_sensor_aht25.cpp"
    INCLUDE_DIRS "include"
    REQUIRES governance_core gov_hal
)
```

`Kconfig.projbuild`. Com `.projbuild`, o menu aparece no primeiro nível do `idf.py menuconfig`, junto com os outros "Sensor: …"; com `Kconfig` ele ficaria escondido em Component config:
```
menu "Sensor: AHT25"

    config GOV_SENSOR_AHT25
        bool "Sensor AHT25 (I2C 0x38)"
        default y

endmenu
```

`include/gov_sensor_aht25.h`
```c
#pragma once
#include "gov_sensor.h"

#ifdef __cplusplus
extern "C" {
#endif

extern const gov_sensor_t gov_sensor_aht25;

#ifdef __cplusplus
}
#endif
```

`src/gov_sensor_aht25.cpp`
```cpp
#include "gov_sensor_aht25.h"
#include "gov_hal.h"

#define AHT25_ADDR 0x38

static bool probe(void) {
    return gov_i2c_probe(AHT25_ADDR);
}

static esp_err_t init(void) {
    // driver setup; return an error to mark the sensor as absent
    return ESP_OK;
}

static int read(gov_reading_t* out, int max) {
    float t, h;
    if (max < 2 /* || reading failed */) return 0;
    out[0] = { "temperature", t };
    out[1] = { "humidity", h };
    return 2;
}

const gov_sensor_t gov_sensor_aht25 = {
    .code             = "aht25",
    .sample_period_ms = 2000,
    .probe            = probe,
    .init             = init,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"aht25\",\"pins\":{\"SDA\":" GOV_STR(CONFIG_GOV_I2C_SDA_PIN)
                                        ",\"SCL\":" GOV_STR(CONFIG_GOV_I2C_SCL_PIN) "},"
                                        "\"metrics\":[\"temperature\",\"humidity\"]}"),
};
```

O `info` é um texto montado na compilação com os valores do menuconfig e gravado dentro do `.bin`. No upload da versão, a plataforma lê esse texto e mostra a tabela "Sensores suportados" (sensor, pino do sensor → GPIO e métricas). Ninguém digita pino na plataforma.

### 2. Ligar no firmware

Em `main/CMakeLists.txt`, adicione o componente ao `REQUIRES`. O projeto usa `MINIMAL_BUILD`, então componente que não é requerido não é compilado.
```cmake
    REQUIRES
        ...
        gov_sensor_aht25
```

Em `main/main.cpp`, registre o sensor em `register_sensors()`:
```cpp
#include "gov_sensor_aht25.h"
...
#if CONFIG_GOV_SENSOR_AHT25
    gov_sensor_register(&gov_sensor_aht25);
#endif
```

### 3. Compilar, subir e gravar

1. **Compilar:** `idf.py build`.
2. **Subir o `.bin` como nova versão na plataforma:** sensores, pinos e métricas vêm do manifesto. Sensores novos entram sozinhos no catálogo, com nome igual ao `code`, e o admin pode renomear na tela Sensores.
3. **Ligar o hardware:** use a tabela da versão para ligar cada sensor.
4. **Atualizar o dispositivo:** faça o OTA. O dispositivo reporta quais sensores detectou de fato, e as leituras entram na telemetria com o nome da métrica.

Como os pinos são definidos na compilação, cada pinagem diferente é uma compilação diferente: suba como outra versão ou outro firmware.

## Regras do contrato

- **`code`:** `[a-z0-9_]{1,24}`, único. Se não seguir a regra, o kernel rejeita o registro e mostra o erro no log.
- **`probe()`:** sem efeitos colaterais além do necessário para detectar o sensor. Só ele é obrigatório além do `code`.
- **`init()`:** opcional. Roda a cada detecção (no boot e em cada reconexão por hot-plug), então precisa poder rodar de novo. Se devolver erro, o sensor é tratado como ausente.
- **`read()`:** opcional; sensores sem leitura, como o DS3231, só aparecem como presentes. Devolve quantas leituras preencheu; `0` quando ainda não há dado válido (sem fix, aquecendo...). Cada leitura sai na telemetria.
- **`metric`:** nome literal (`"temperature"`), no máximo 15 caracteres.
- **`info`:** use `GOV_SENSOR_INFO` e `GOV_STR(CONFIG_...)`, nunca números fixos, para o manifesto acompanhar o menuconfig. Os pinos têm o nome impresso **no módulo do sensor**: no GPS, `TX` é o TX do módulo, ligado ao GPIO de RX do ESP. Sem `info`, o sensor funciona, mas a plataforma não mostra os pinos dele.
- **Pinos de ADC:** são configurados por GPIO (ADC1: GPIO1 a GPIO10), não por canal.
- **`hotplug`:** se `true`, o kernel refaz o `probe()` a cada 30 s com o sistema rodando. O sensor ligado depois do boot entra na lista; o desligado sai, e a plataforma registra o evento. Use só quando o `probe()` for seguro de repetir enquanto o sensor está em uso: endereço I2C ou leitura de ADC sim; reinstalar a UART em uso, como no GPS, não.
- **`sample_period_ms`:** com período `0`, o sensor é lido só na hora da telemetria. Valor sem amostragem há mais de 3 períodos não é enviado.
- **Limites:** no máximo 16 sensores e 4 métricas por sensor. A telemetria leva até 16 leituras no total.
- **Barramentos:** use os do `gov_hal` (`gov_i2c_probe`, `gov_adc_config`, `gov_adc_read_mv`). Os pinos de I2C ficam no menuconfig.

## Testar o kernel

```
governance_core/test/host/run.sh
```
Roda no PC (g++), sem placa, com sensores falsos: registro, descoberta, amostragem, cache vencido e montagem da telemetria.
