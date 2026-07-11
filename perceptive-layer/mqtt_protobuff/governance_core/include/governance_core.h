#pragma once

#include "esp_err.h"
#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

// Max sensor readings per telemetry publish
// Adjust based on your device sensor limit
#define GOVERNANCE_MAX_READINGS 8


// One sensor reading: key (channel name) + float value
// The pair (key, value) is mapped as SensorReading on DeviceTemeletry protobuf
typedef struct {
    char key[16];
    float value;
} sensor_reading_t;


// Hooks that the specific firmware implements
// All fields are optional, if NULL, it assumes default behavior
typedef struct {


    // Called once after connecting to MQTT (SENSOR_INIT state)
    // Must probe avaiable sensors and return CSV with name of detected ones
    // If returns NULL or empty string -> no sensor detected
    const char* (*sensor_discovery)(void);

    // Called each TELEMETRY_INTERVAL_MS (config Kconfig).
    // Fills 'out[]' with up to 'max' readings. Return the number of readings filled
    // If NULL, telemetry_task publish nothing.
    int (*read_telemetry)(sensor_reading_t* out, int max);

    // Time sync (optional)
    // Todos os 3 são independentes. Core usa a combinação disponível:
    //   * só get_persisted_time  → hora vem do RTC persistente (DS3231, PCF8563, etc)
    //   * só get_external_time   → hora vem de fonte externa (GPS, NTP, ...) em cada boot
    //   * ambos                  → RTC como primário; external refresca DS3231 a cada
    //                              GOV_GPS_SYNC_EVERY_N_BOOTS boots (config Kconfig)
    //   * nenhum                 → sem sync, sistema roda sem hora sincronizada

    // Lê hora persistida. Retorna 0 se sem hora válida ou hardware ausente.
    time_t   (*get_persisted_time)(void);

    // Grava hora no RTC persistente. Chamado após get_external_time bem-sucedido
    // pra manter a hora sobrevivendo reboots.
    void     (*persist_time)(time_t epoch);

    // Bloqueia até obter hora de fonte externa OU timeout_ms.
    // Retorna 0 se timeout ou falha.
    time_t   (*get_external_time)(uint32_t timeout_ms);

    // ---- Bateria (opcional, futuro low-battery event) ------------------------
    // Retorna nível atual em mV. 0 se hardware ausente. Chamado ~1x/min.
    uint32_t (*get_battery_mv)(void);

} governance_hooks_t;

// Firmware entry point
// Blocking - orchestrator state machine + internal FreeRTOS tasks.
// Returns only in the event of a catastrophic initialization failure.
esp_err_t governance_core_init(const governance_hooks_t* hooks);

#ifdef __cplusplus
}
#endif
