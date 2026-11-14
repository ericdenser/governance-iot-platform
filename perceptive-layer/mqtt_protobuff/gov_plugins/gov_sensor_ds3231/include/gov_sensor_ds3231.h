#pragma once
#include <time.h>
#include "gov_sensor.h"

#ifdef __cplusplus
extern "C" {
#endif

extern const gov_sensor_t gov_sensor_ds3231;

// For the get_persisted_time / persist_time hooks. read returns 0 if absent or not set.
time_t gov_ds3231_read_time(void);
void   gov_ds3231_write_time(time_t epoch);

#ifdef __cplusplus
}
#endif
