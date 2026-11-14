#include "gov_sensor_ds3231.h"

#include "sdkconfig.h"
#include "ds3231.h"
#include "gov_hal.h"

#define DS3231_ADDR 0x68
#define DS3231_HZ   400000

static bool probe(void) {
    return gov_i2c_probe(DS3231_ADDR);
}

static bool open_rtc(i2c_dev_t* rtc) {
    if (!probe()) return false;
    *rtc = {};
    if (ds3231_init_desc(rtc, GOV_I2C_PORT, (gpio_num_t)CONFIG_GOV_I2C_SDA_PIN,
                         (gpio_num_t)CONFIG_GOV_I2C_SCL_PIN) != ESP_OK) return false;
    rtc->cfg.sda_pullup_en    = true;
    rtc->cfg.scl_pullup_en    = true;
    rtc->cfg.master.clk_speed = DS3231_HZ;
    return true;
}

time_t gov_ds3231_read_time(void) {
    i2c_dev_t rtc;
    if (!open_rtc(&rtc)) return 0;

    struct tm ti = {};
    time_t result = 0;
    // year >= 2024: older means the RTC was never set
    if (ds3231_get_time(&rtc, &ti) == ESP_OK && ti.tm_year >= 124) {
        ti.tm_isdst = 0;
        result = mktime(&ti);
    }
    ds3231_free_desc(&rtc);
    return result;
}

void gov_ds3231_write_time(time_t epoch) {
    i2c_dev_t rtc;
    if (!open_rtc(&rtc)) return;

    struct tm ti;
    gmtime_r(&epoch, &ti);
    ds3231_set_time(&rtc, &ti);
    ds3231_free_desc(&rtc);
}

const gov_sensor_t gov_sensor_ds3231 = {
    .code             = "ds3231",
    .sample_period_ms = 0,
    .probe            = probe,
    .init             = nullptr,
    .read             = nullptr,
    .info             = GOV_SENSOR_INFO("{\"code\":\"ds3231\",\"pins\":{\"SDA\":" GOV_STR(CONFIG_GOV_I2C_SDA_PIN) ",\"SCL\":" GOV_STR(CONFIG_GOV_I2C_SCL_PIN) "},\"metrics\":[]}"),
    .hotplug          = true,
};
