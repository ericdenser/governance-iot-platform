#include "gov_sensor_bmp280.h"

#include <string.h>
#include "sdkconfig.h"
#include "esp_log.h"
#include "bmp280.h"
#include "gov_hal.h"

static const char* TAG = "bmp280";

static bmp280_t s_dev;
static uint8_t  s_addr = BMP280_I2C_ADDRESS_0;
static bool     s_desc = false;

static bool probe(void) {
    if (gov_i2c_probe(BMP280_I2C_ADDRESS_0)) { s_addr = BMP280_I2C_ADDRESS_0; return true; }
    if (gov_i2c_probe(BMP280_I2C_ADDRESS_1)) { s_addr = BMP280_I2C_ADDRESS_1; return true; }
    return false;
}

static esp_err_t init(void) {
    bmp280_params_t params;
    bmp280_init_default_params(&params);

    esp_err_t err = ESP_OK;
    // the I2C descriptor is created once; the chip is reconfigured on every reconnection
    if (!s_desc) {
        memset(&s_dev, 0, sizeof(s_dev));
        err = bmp280_init_desc(&s_dev, s_addr, GOV_I2C_PORT,
                               (gpio_num_t)CONFIG_GOV_I2C_SDA_PIN, (gpio_num_t)CONFIG_GOV_I2C_SCL_PIN);
        s_desc = (err == ESP_OK);
    }
    if (err == ESP_OK) err = bmp280_init(&s_dev, &params);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "%s em 0x%02X", s_dev.id == BME280_CHIP_ID ? "BME280" : "BMP280", s_addr);
    }
    return err;
}

static int read(gov_reading_t* out, int max) {
    bool bme = (s_dev.id == BME280_CHIP_ID);
    float t, p, h = 0.0f;
    if (max < 3 || bmp280_read_float(&s_dev, &t, &p, bme ? &h : nullptr) != ESP_OK) return 0;

    out[0] = { "temperature", t };
    out[1] = { "pressure", p / 100.0f };
    if (!bme) return 2;
    out[2] = { "humidity", h };
    return 3;
}

const gov_sensor_t gov_sensor_bmp280 = {
    .code             = "bmp280",
    .sample_period_ms = 2000,
    .probe            = probe,
    .init             = init,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"bmp280\",\"pins\":{\"SDA\":" GOV_STR(CONFIG_GOV_I2C_SDA_PIN) ",\"SCL\":" GOV_STR(CONFIG_GOV_I2C_SCL_PIN) "},"
                                        "\"metrics\":[\"temperature\",\"pressure\",\"humidity\"]}"),
    .hotplug          = true,
};
