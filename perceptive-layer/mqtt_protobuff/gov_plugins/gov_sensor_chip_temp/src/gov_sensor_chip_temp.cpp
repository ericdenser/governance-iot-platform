#include "gov_sensor_chip_temp.h"

#include "soc/soc_caps.h"
#if SOC_TEMP_SENSOR_SUPPORTED
#include "driver/temperature_sensor.h"

static temperature_sensor_handle_t s_handle = nullptr;

static bool probe(void) { return true; }

static esp_err_t init(void) {
    temperature_sensor_config_t cfg = TEMPERATURE_SENSOR_CONFIG_DEFAULT(10, 80);
    esp_err_t err = temperature_sensor_install(&cfg, &s_handle);
    if (err == ESP_OK) err = temperature_sensor_enable(s_handle);
    return err;
}

static int read(gov_reading_t* out, int max) {
    float c;
    if (max < 1 || temperature_sensor_get_celsius(s_handle, &c) != ESP_OK) return 0;
    out[0] = { "temp_c", c };
    return 1;
}

#else

static bool probe(void) { return false; }
static esp_err_t init(void) { return ESP_ERR_NOT_SUPPORTED; }
static int read(gov_reading_t*, int) { return 0; }

#endif

const gov_sensor_t gov_sensor_chip_temp = {
    .code             = "chip_temp",
    .sample_period_ms = 2000,
    .probe            = probe,
    .init             = init,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"chip_temp\",\"pins\":{},\"metrics\":[\"temp_c\"]}"),
};
