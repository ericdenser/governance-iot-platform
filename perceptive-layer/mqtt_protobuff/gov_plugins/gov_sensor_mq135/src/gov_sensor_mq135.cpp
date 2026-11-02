#include "gov_sensor_mq135.h"

#include <math.h>
#include "sdkconfig.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "gov_hal.h"

#if CONFIG_GOV_MQ135_ENABLED

// ppm = A * (Rs/R0)^B (CO2 curve from G. Krocker's MQ135 lib)
#define MQ135_PARA   116.6020682f
#define MQ135_PARB   -2.769034857f
#define MQ135_VC_MV  5000.0f
#define MQ135_MIN_MV 50.0f
#define MQ135_EMA    0.3f
#define MQ135_LOG_US (30LL * 1000000)

static const char* TAG = "Mq135";

static bool  s_configured = false;
static float s_ema        = -1.0f;

// Logs when the signal appears/disappears and every 30 s
static void log_diag(bool present, int pin_mv, float vs, float rs, float ppm) {
    static int     last_present = -1;
    static int64_t last_us      = 0;
    int64_t now = esp_timer_get_time();
    if ((int)present == last_present && now - last_us < MQ135_LOG_US) return;
    last_present = present;
    last_us      = now;

    if (present) {
        ESP_LOGI(TAG, "pino=%d mV sensor=%.0f mV Rs=%.0f ohm ppm=%.0f", pin_mv, vs, rs, ppm);
    } else {
        ESP_LOGW(TAG, "sem sinal: pino=%d mV (sensor=%.0f mV < %.0f). Conferir AO (nao DO), divisor e GPIO%d",
                 pin_mv, vs, MQ135_MIN_MV, CONFIG_GOV_MQ135_GPIO);
    }
}

// false = no signal on AO
static bool measure(float* ppm) {
    if (!s_configured) {
        gov_adc_config(CONFIG_GOV_MQ135_GPIO);
        s_configured = true;
    }

    int pin_mv = gov_adc_read_mv(CONFIG_GOV_MQ135_GPIO, false);
    float vs = (pin_mv < 0) ? 0.0f : pin_mv * (CONFIG_GOV_MQ135_DIVIDER_X100 / 100.0f);
    if (vs < MQ135_MIN_MV) {
        s_ema = -1.0f;
        log_diag(false, pin_mv, vs, 0.0f, 0.0f);
        return false;
    }
    if (vs > MQ135_VC_MV - 1.0f) vs = MQ135_VC_MV - 1.0f;

    float rs  = CONFIG_GOV_MQ135_RL_OHM * (MQ135_VC_MV - vs) / vs;
    float raw = MQ135_PARA * powf(rs / CONFIG_GOV_MQ135_R0_OHM, MQ135_PARB);
    if (raw > 10000.0f) raw = 10000.0f;

    s_ema = (s_ema < 0.0f) ? raw : (MQ135_EMA * raw + (1.0f - MQ135_EMA) * s_ema);
    *ppm = s_ema;
    log_diag(true, pin_mv, vs, rs, s_ema);
    return true;
}

static bool probe(void) {
    float ppm;
    return measure(&ppm);
}

static int read(gov_reading_t* out, int max) {
    float ppm;
    // keeps the EMA running during the warm-up, but only reports after it
    if (max < 1 || !measure(&ppm) || gov_mq135_warmup_left_s() > 0) return 0;
    out[0] = { "co2_ppm", ppm };
    return 1;
}

#else

static bool probe(void) { return false; }
static int read(gov_reading_t*, int) { return 0; }

#endif

int gov_mq135_warmup_left_s(void) {
#if CONFIG_GOV_MQ135_ENABLED
    int left = CONFIG_GOV_MQ135_WARMUP_S - (int)(esp_timer_get_time() / 1000000);
    return left > 0 ? left : 0;
#else
    return 0;
#endif
}

gov_air_level_t gov_mq135_classify(float ppm) {
    if (ppm < 800.0f)  return GOV_AIR_GOOD;
    if (ppm < 1200.0f) return GOV_AIR_MODERATE;
    if (ppm < 2000.0f) return GOV_AIR_POOR;
    return GOV_AIR_BAD;
}

const char* gov_mq135_level_text(gov_air_level_t level) {
    switch (level) {
        case GOV_AIR_GOOD:     return "Bom";
        case GOV_AIR_MODERATE: return "Moderado";
        case GOV_AIR_POOR:     return "Ruim";
        case GOV_AIR_BAD:      return "Pessimo";
        default:               return "-";
    }
}

const gov_sensor_t gov_sensor_mq135 = {
    .code             = "mq135",
    .sample_period_ms = 2000,
    .probe            = probe,
    .init             = nullptr,
    .read             = read,
    .info             = GOV_SENSOR_INFO("{\"code\":\"mq135\",\"pins\":{\"AO\":" GOV_STR(CONFIG_GOV_MQ135_GPIO) "},"
                                        "\"metrics\":[\"co2_ppm\"]}"),
};
