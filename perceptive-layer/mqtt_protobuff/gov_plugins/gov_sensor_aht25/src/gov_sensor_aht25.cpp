#include "gov_sensor_aht25.h"

#include <string.h>
#include "sdkconfig.h"
#include "esp_log.h"
#include "i2cdev.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "gov_hal.h"

static const char* TAG = "aht25";

#define AHT25_ADDR            0x38
#define AHT25_HZ              100000
#define AHT_STATUS_BUSY       0x80
#define AHT_STATUS_CALIBRATED 0x18

static i2c_dev_t s_dev;
static bool      s_desc = false;

// CRC-8, poly 0x31, init 0xFF (datasheet section 5.4)
static uint8_t crc8(const uint8_t* data, int len) {
    uint8_t crc = 0xFF;
    for (int i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++) {
            crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
        }
    }
    return crc;
}

static bool ensure_calibrated(void) {
    uint8_t status = 0;
    if (i2c_dev_read(&s_dev, nullptr, 0, &status, 1) != ESP_OK) return false;
    if ((status & AHT_STATUS_CALIBRATED) == AHT_STATUS_CALIBRATED) return true;

    // not calibrated: init command (0xBE 0x08 0x00) and check again
    const uint8_t cmd[] = {0xBE, 0x08, 0x00};
    if (i2c_dev_write(&s_dev, nullptr, 0, cmd, sizeof(cmd)) != ESP_OK) return false;
    vTaskDelay(pdMS_TO_TICKS(10));
    if (i2c_dev_read(&s_dev, nullptr, 0, &status, 1) != ESP_OK) return false;
    return (status & AHT_STATUS_CALIBRATED) == AHT_STATUS_CALIBRATED;
}

static bool probe(void) {
    return gov_i2c_probe(AHT25_ADDR);
}

static esp_err_t init(void) {
    if (!s_desc) {
        memset(&s_dev, 0, sizeof(s_dev));
        s_dev.port                 = GOV_I2C_PORT;
        s_dev.addr                 = AHT25_ADDR;
        s_dev.cfg.sda_io_num       = (gpio_num_t)CONFIG_GOV_I2C_SDA_PIN;
        s_dev.cfg.scl_io_num       = (gpio_num_t)CONFIG_GOV_I2C_SCL_PIN;
        s_dev.cfg.sda_pullup_en    = true;
        s_dev.cfg.scl_pullup_en    = true;
        s_dev.cfg.master.clk_speed = AHT25_HZ;
        if (i2c_dev_create_mutex(&s_dev) != ESP_OK) return ESP_FAIL;
        s_desc = true;
    }

    // datasheet: >= 100 ms after power-on before the first command
    vTaskDelay(pdMS_TO_TICKS(100));
    if (i2c_dev_take_mutex(&s_dev) != ESP_OK) return ESP_FAIL;
    bool ok = ensure_calibrated();
    i2c_dev_give_mutex(&s_dev);

    if (!ok) {
        ESP_LOGE(TAG, "AHT25 em 0x%02X nao calibrou", AHT25_ADDR);
        return ESP_FAIL;
    }
    ESP_LOGI(TAG, "AHT25 em 0x%02X", AHT25_ADDR);
    return ESP_OK;
}

static int read(gov_reading_t* out, int max) {
    if (max < 2) return 0;

    const uint8_t trigger[] = {0xAC, 0x33, 0x00};
    uint8_t buf[7] = {};
    bool ok = false;

    if (i2c_dev_take_mutex(&s_dev) != ESP_OK) return 0;
    if (i2c_dev_write(&s_dev, nullptr, 0, trigger, sizeof(trigger)) == ESP_OK) {
        vTaskDelay(pdMS_TO_TICKS(80));
        for (int tries = 0; tries < 5; tries++) {
            if (i2c_dev_read(&s_dev, nullptr, 0, buf, sizeof(buf)) != ESP_OK) break;
            if (!(buf[0] & AHT_STATUS_BUSY)) { ok = true; break; }
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }
    i2c_dev_give_mutex(&s_dev);

    if (!ok) return 0;
    if (crc8(buf, 6) != buf[6]) {
        ESP_LOGW(TAG, "CRC invalido, amostra descartada");
        return 0;
    }

    uint32_t raw_h = ((uint32_t)buf[1] << 12) | ((uint32_t)buf[2] << 4) | (buf[3] >> 4);
    uint32_t raw_t = (((uint32_t)buf[3] & 0x0F) << 16) | ((uint32_t)buf[4] << 8) | buf[5];
    out[0] = { "temperature", (float)raw_t * 200.0f / 1048576.0f - 50.0f };
    out[1] = { "humidity", (float)raw_h * 100.0f / 1048576.0f };
    return 2;
}

const gov_sensor_t gov_sensor_aht25 = {
    .code             = "aht25",
    .sample_period_ms = 2000,
    .probe            = probe,
    .init             = init,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"aht25\",\"pins\":{\"SDA\":" GOV_STR(CONFIG_GOV_I2C_SDA_PIN)
                                        ",\"SCL\":" GOV_STR(CONFIG_GOV_I2C_SCL_PIN) "},"
                                        "\"metrics\":[\"temperature\",\"humidity\"]}"),
    .hotplug          = true,
};
