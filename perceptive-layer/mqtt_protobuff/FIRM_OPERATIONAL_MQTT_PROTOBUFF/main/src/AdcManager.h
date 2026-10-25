#pragma once
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

class AdcManager {
public:
    static void init();
    static void configChannel(adc_channel_t channel);
    static int  readMilliVolts(adc_channel_t channel, bool verbose = true);

private:
    static adc_oneshot_unit_handle_t _adc_handle;
    static adc_cali_handle_t         _adc_cali_handle;
    static bool                      _is_initialized;
    static SemaphoreHandle_t         _mutex;  // oneshot nao e thread-safe
};
