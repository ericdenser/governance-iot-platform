#include "gov_sensor_battery.h"

#include <stdlib.h>
#include "sdkconfig.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "gov_hal.h"

static const char* TAG = "battery";

#define BAT_DIVIDER (CONFIG_GOV_BAT_DIVIDER_X100 / 100.0f)

static void config_once(void) {
    static bool configured = false;
    if (configured) return;
    gov_adc_config(CONFIG_GOV_BAT_GPIO);
    configured = true;
}

float gov_battery_read_mv(void) {
    config_once();
    int pin_mv = gov_adc_read_mv(CONFIG_GOV_BAT_GPIO, true);
    if (pin_mv < 0) return -1.0f;

    // below the LiPo cut-off it is a floating pin or unstable ADC, never real charge
    float mv = (float)pin_mv * BAT_DIVIDER;
    return mv < CONFIG_GOV_BAT_MIN_VALID_MV ? -1.0f : mv;
}

// A real battery gives two close readings inside the expected range
static bool probe(void) {
    config_once();
    (void)gov_adc_read_mv(CONFIG_GOV_BAT_GPIO, false);
    vTaskDelay(pdMS_TO_TICKS(50));
    int mv1 = gov_adc_read_mv(CONFIG_GOV_BAT_GPIO, true);
    vTaskDelay(pdMS_TO_TICKS(50));
    int mv2 = gov_adc_read_mv(CONFIG_GOV_BAT_GPIO, true);

    int diff = abs(mv1 - mv2);
    int avg  = (mv1 + mv2) / 2;
    if (diff < 100 && avg > 500 && avg < 5000) return true;

    ESP_LOGW(TAG, "ADC bateria ausente/fantasma (mv1=%d mv2=%d diff=%d)", mv1, mv2, diff);
    return false;
}

static int read(gov_reading_t* out, int max) {
    float mv = gov_battery_read_mv();
    if (max < 1 || mv <= 0.0f) return 0;
    out[0] = { "battery_mv", mv };
    return 1;
}

const gov_sensor_t gov_sensor_battery = {
    .code             = "battery_adc",
    .sample_period_ms = 10000,
    .probe            = probe,
    .init             = nullptr,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"battery_adc\",\"pins\":{\"ADC\":" GOV_STR(CONFIG_GOV_BAT_GPIO) "},"
                                        "\"metrics\":[\"battery_mv\"]}"),
};
