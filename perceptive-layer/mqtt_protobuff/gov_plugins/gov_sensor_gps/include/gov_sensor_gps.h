#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <time.h>
#include "gov_sensor.h"

#ifdef __cplusplus
extern "C" {
#endif

extern const gov_sensor_t gov_sensor_gps;

// Power transistor on CONFIG_GOV_GPS_POWER_PIN (call gov_gps_power(false) at boot)
void gov_gps_power(bool on);

// For the get_external_time hook: waits for a fix and returns its UTC time, 0 on timeout
time_t gov_gps_wait_time(uint32_t timeout_ms);

#ifdef __cplusplus
}
#endif
