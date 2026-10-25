#include "ChipTempManager.h"

#include "soc/soc_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "driver/temperature_sensor.h"

static SemaphoreHandle_t s_mutex = nullptr;
#if SOC_TEMP_SENSOR_SUPPORTED
static temperature_sensor_handle_t s_handle = nullptr;
static bool                        s_failed = false;
#endif

void ChipTempManager::init() {
    if (!s_mutex) s_mutex = xSemaphoreCreateMutex();
}

bool ChipTempManager::read(float& celsius) {
#if SOC_TEMP_SENSOR_SUPPORTED
    bool ok = false;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    if (s_handle == nullptr && !s_failed) {
        temperature_sensor_config_t cfg = TEMPERATURE_SENSOR_CONFIG_DEFAULT(10, 80);
        if (temperature_sensor_install(&cfg, &s_handle) == ESP_OK) {
            temperature_sensor_enable(s_handle);
        } else {
            s_handle = nullptr;
            s_failed = true;
        }
    }
    if (s_handle != nullptr) {
        ok = (temperature_sensor_get_celsius(s_handle, &celsius) == ESP_OK);
    }
    xSemaphoreGive(s_mutex);
    return ok;
#else
    (void)celsius;
    return false;
#endif
}
