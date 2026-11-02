#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "driver/i2c_types.h"

#ifdef __cplusplus
extern "C" {
#endif

// Shared buses for sensor plugins (I2C on CONFIG_GOV_I2C_*_PIN, ADC1 calibrated)
#define GOV_I2C_PORT I2C_NUM_0

void gov_hal_init(void);
bool gov_i2c_probe(uint8_t addr);

// ADC1 by GPIO (ESP32-S3: GPIO1..GPIO10). ADC2 conflicts with WiFi.
bool gov_adc_config(int gpio);
// Average of 16 samples in mV, -1 on failure
int  gov_adc_read_mv(int gpio, bool verbose);

#ifdef __cplusplus
}
#endif
