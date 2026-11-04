#include "gov_sensor_env.h"

#include "sdkconfig.h"
#include "esp_log.h"
#include "EnvFusion.h"

#if CONFIG_GOV_SENSOR_ENV

static const char* TAG = "env";

#define MAX_AGE_MS  6000   // 3 periods of the physical sensors
#define LOG_EVERY   5      // raw values on the serial every 10 s

static EnvFusion s_temp(CONFIG_GOV_ENV_TEMP_OUTLIER_X10 / 10.0f);
static EnvFusion s_hum(CONFIG_GOV_ENV_HUM_OUTLIER_X10 / 10.0f);

enum { SRC_BMP = 0, SRC_AHT = 1, SRC_COUNT };

static bool fresh(const char* code, const char* metric, float* v) {
    int64_t age = 0;
    return gov_sensor_get(code, metric, v, &age) && age < MAX_AGE_MS;
}

static bool probe(void) {
    return gov_sensor_is_active("bmp280") || gov_sensor_is_active("aht25");
}

static int read(gov_reading_t* out, int max) {
    float bt = 0, bp = 0, bh = 0, at = 0, ah = 0;
    bool bmp     = fresh("bmp280", "temperature", &bt);
    bool press   = fresh("bmp280", "pressure", &bp);
    bool bme_hum = fresh("bmp280", "humidity", &bh);
    bool aht     = fresh("aht25", "temperature", &at) && fresh("aht25", "humidity", &ah);

    int n = 0;
    float t = 0, h = 0;
    uint8_t mask = 0;
    const float temps[SRC_COUNT] = {bt, at};
    const bool  t_ok[SRC_COUNT]  = {bmp, aht};
    if (n < max && s_temp.fuse(temps, t_ok, SRC_COUNT, t, &mask)) out[n++] = { "temperature", t };

    const float hums[SRC_COUNT] = {bh, ah};
    const bool  h_ok[SRC_COUNT] = {bme_hum, aht};
    if (n < max && s_hum.fuse(hums, h_ok, SRC_COUNT, h)) out[n++] = { "humidity", h };

    if (n < max && press) out[n++] = { "pressure", bp };

    if (bmp && aht && mask != ((1u << SRC_BMP) | (1u << SRC_AHT))) {
        ESP_LOGW(TAG, "Outlier de temperatura descartado: BMP %.2f C, AHT %.2f C -> %.2f C (%s)",
                 bt, at, t, (mask & (1u << SRC_BMP)) ? "AHT descartado" : "BMP descartado");
    }

    static int calls = 0;
    if (++calls % LOG_EVERY == 0) {
        ESP_LOGI(TAG, "BMP %s %.2f C | AHT %s %.2f C %.1f %%RH | fusao %.2f C (mask %u) | %.1f hPa",
                 bmp ? "ok" : "--", bt, aht ? "ok" : "--", at, ah, t, mask, bp);
    }
    return n;
}

const gov_sensor_t gov_sensor_env = {
    .code             = "env",
    .sample_period_ms = 2000,
    .probe            = probe,
    .init             = nullptr,
    .read             = read,
    .info             = nullptr,
    .hotplug          = true,
};

#endif
