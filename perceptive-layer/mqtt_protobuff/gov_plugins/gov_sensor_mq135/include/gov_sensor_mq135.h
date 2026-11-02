#pragma once
#include "gov_sensor.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    GOV_AIR_UNKNOWN,
    GOV_AIR_GOOD,
    GOV_AIR_MODERATE,
    GOV_AIR_POOR,
    GOV_AIR_BAD,
} gov_air_level_t;

extern const gov_sensor_t gov_sensor_mq135;

int             gov_mq135_warmup_left_s(void);
gov_air_level_t gov_mq135_classify(float ppm);
const char*     gov_mq135_level_text(gov_air_level_t level);

#ifdef __cplusplus
}
#endif
