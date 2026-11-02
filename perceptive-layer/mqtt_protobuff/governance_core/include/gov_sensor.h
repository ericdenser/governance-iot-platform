#pragma once

#include "esp_err.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "governance_core.h"

#ifdef __cplusplus
extern "C" {
#endif

#define GOV_SENSOR_MAX          16
#define GOV_SENSOR_MAX_READINGS 4

typedef struct {
    const char* metric;   // string literal, e.g. "temperature"
    float       value;
} gov_reading_t;

// Sensor plugin. Only code and probe are mandatory.
typedef struct {
    const char* code;                // reported to the platform, [a-z0-9_]{1,24}
    uint32_t    sample_period_ms;    // 0 = read only when telemetry is sent

    bool      (*probe)(void);
    esp_err_t (*init)(void);         // on every detection (boot or hot-plug), must be safe to repeat
    int       (*read)(gov_reading_t* out, int max);

    const char* info;                // GOV_SENSOR_INFO(...), read by the platform from the .bin
    bool        hotplug;             // probe() is re-run while running to detect plug/unplug
} gov_sensor_t;

#define GOV_STR_(x) #x
#define GOV_STR(x)  GOV_STR_(x)

// Pins (as named on the sensor module) and metrics, e.g.
// GOV_SENSOR_INFO("{\"code\":\"x\",\"pins\":{\"SDA\":" GOV_STR(CONFIG_X_SDA) "},\"metrics\":[\"t\"]}")
#define GOV_SENSOR_INFO(json) "GOVSENSOR:" json

// Call before governance_core_init()
esp_err_t gov_sensor_register(const gov_sensor_t* sensor);
int       gov_sensor_count(void);

// Last sampled value of a metric of an active sensor
bool gov_sensor_get(const char* code, const char* metric, float* value, int64_t* age_ms);
bool gov_sensor_is_active(const char* code);

// CSV of active sensor codes, copied into out (empty until discovery runs)
void gov_sensors_active_csv(char* out, size_t len);

// Used by the core
const char* gov_sensors_discover(void);
int         gov_sensors_collect(sensor_reading_t* out, int max);
void        gov_sensors_sample_due(int64_t now_us);
// Re-probes hotplug sensors; true if the active list changed
bool        gov_sensors_rescan(int64_t now_us);

#ifdef __cplusplus
}
#endif
