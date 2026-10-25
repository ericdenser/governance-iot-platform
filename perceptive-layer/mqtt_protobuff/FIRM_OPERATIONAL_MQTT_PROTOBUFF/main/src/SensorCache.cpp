#include "SensorCache.h"

#include "sdkconfig.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "BmpManager.h"
#include "ChipTempManager.h"

static const char* TAG = "SensorCache";

using SensorCache::SAMPLE_PERIOD_MS;

static SemaphoreHandle_t s_mutex   = nullptr;
static SensorSnapshot    s_snap    = {};
static BmpManager*       s_bme     = nullptr;
static bool              s_started = false;

static void sample(SensorSnapshot& s) {
    float t, p, h;
    s.env_valid = s_bme && s_bme->readAll(t, p, h);
    s.hum_valid = s.env_valid && s_bme->isBme();
    if (s.env_valid) {
        s.temp_c    = t;
        s.press_hpa = p / 100.0f;
        s.hum_pct   = h;
    }

    float ppm;
    bool present = Mq135Manager::read(ppm);
    int  warmup  = Mq135Manager::warmupLeftS();
    s.air_warming       = present && warmup > 0;
    s.air_warmup_left_s = warmup;
    s.air_valid         = present && warmup == 0;
    s.co2_ppm           = s.air_valid ? ppm : 0.0f;
    s.air_level         = s.air_valid ? Mq135Manager::classify(ppm) : AirLevel::UNKNOWN;

    float chip;
    s.chip_valid = ChipTempManager::read(chip);
    s.chip_c     = chip;
}

static void sampler_task(void*) {
    while (true) {
        SensorSnapshot fresh = {};
        sample(fresh);
        fresh.sampled_us = esp_timer_get_time();

        xSemaphoreTake(s_mutex, portMAX_DELAY);
        fresh.bat_valid = s_snap.bat_valid;
        fresh.bat_mv    = s_snap.bat_mv;
        s_snap = fresh;
        xSemaphoreGive(s_mutex);

        vTaskDelay(pdMS_TO_TICKS(SAMPLE_PERIOD_MS));
    }
}

namespace SensorCache {

void init() {
    if (!s_mutex) s_mutex = xSemaphoreCreateMutex();
}

void start(BmpManager* bme) {
    if (s_started) return;
    s_bme     = bme;
    s_started = true;
    xTaskCreate(sampler_task, "sensor_cache", 4096, nullptr, 2, nullptr);
    ESP_LOGI(TAG, "Amostragem a cada %d ms (BME/BMP: %s, MQ135: %s)", SAMPLE_PERIOD_MS,
             bme ? (bme->isBme() ? "BME280" : "BMP280") : "nao",
             CONFIG_GOV_MQ135_ENABLED ? "sim" : "nao");
}

SensorSnapshot get() {
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    SensorSnapshot copy = s_snap;
    xSemaphoreGive(s_mutex);
    return copy;
}

bool getFresh(SensorSnapshot& out, int max_age_ms) {
    out = get();
    return out.sampled_us > 0 &&
           (esp_timer_get_time() - out.sampled_us) < (int64_t)max_age_ms * 1000;
}

void setBattery(float mv) {
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    s_snap.bat_valid = (mv > 0.0f);
    s_snap.bat_mv    = mv;
    xSemaphoreGive(s_mutex);
}

}  // namespace SensorCache
