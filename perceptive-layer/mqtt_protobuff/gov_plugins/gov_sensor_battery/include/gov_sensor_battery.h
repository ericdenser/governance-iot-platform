#pragma once
#include "gov_sensor.h"

#ifdef __cplusplus
extern "C" {
#endif

extern const gov_sensor_t gov_sensor_battery;

// Direct read for the get_battery_mv hook (also used before discovery). -1 = invalid.
float gov_battery_read_mv(void);

#ifdef __cplusplus
}
#endif
