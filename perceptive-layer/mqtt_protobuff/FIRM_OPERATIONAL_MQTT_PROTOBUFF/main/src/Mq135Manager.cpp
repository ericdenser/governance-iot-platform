#include "Mq135Manager.h"

#include <math.h>
#include "sdkconfig.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "AdcManager.h"

// ppm = A * (Rs/R0)^B (curva de CO2 da lib MQ135 do G. Krocker)
#define MQ135_PARA   116.6020682f
#define MQ135_PARB   -2.769034857f
#define MQ135_VC_MV  5000.0f
#define MQ135_MIN_MV 50.0f
#define MQ135_EMA    0.3f
#define MQ135_LOG_US (30LL * 1000000)

static const char* TAG = "Mq135";

// Loga quando o sinal aparece/some e a cada 30 s
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
        ESP_LOGW(TAG, "sem sinal: pino=%d mV (sensor=%.0f mV < %.0f). Conferir AO (nao DO), divisor e GPIO do canal %d",
                 pin_mv, vs, MQ135_MIN_MV, CONFIG_GOV_MQ135_ADC_CHANNEL);
    }
}

bool Mq135Manager::read(float& ppm) {
#if CONFIG_GOV_MQ135_ENABLED
    static bool  configured = false;
    static float ema        = -1.0f;

    adc_channel_t ch = (adc_channel_t)CONFIG_GOV_MQ135_ADC_CHANNEL;
    if (!configured) {
        AdcManager::configChannel(ch);
        configured = true;
    }

    int pin_mv = AdcManager::readMilliVolts(ch, false);
    float vs = (pin_mv < 0) ? 0.0f : pin_mv * (CONFIG_GOV_MQ135_DIVIDER_X100 / 100.0f);
    if (vs < MQ135_MIN_MV) {
        ema = -1.0f;
        log_diag(false, pin_mv, vs, 0.0f, 0.0f);
        return false;
    }
    if (vs > MQ135_VC_MV - 1.0f) vs = MQ135_VC_MV - 1.0f;

    float rs  = CONFIG_GOV_MQ135_RL_OHM * (MQ135_VC_MV - vs) / vs;
    float raw = MQ135_PARA * powf(rs / CONFIG_GOV_MQ135_R0_OHM, MQ135_PARB);
    if (raw > 10000.0f) raw = 10000.0f;

    ema = (ema < 0.0f) ? raw : (MQ135_EMA * raw + (1.0f - MQ135_EMA) * ema);
    ppm = ema;
    log_diag(true, pin_mv, vs, rs, ema);
    return true;
#else
    (void)ppm;
    return false;
#endif
}

int Mq135Manager::warmupLeftS() {
    int uptime_s = (int)(esp_timer_get_time() / 1000000);
    int left = CONFIG_GOV_MQ135_WARMUP_S - uptime_s;
    return left > 0 ? left : 0;
}

AirLevel Mq135Manager::classify(float ppm) {
    if (ppm < 800.0f)  return AirLevel::GOOD;
    if (ppm < 1200.0f) return AirLevel::MODERATE;
    if (ppm < 2000.0f) return AirLevel::POOR;
    return AirLevel::BAD;
}

const char* Mq135Manager::levelText(AirLevel level) {
    switch (level) {
        case AirLevel::GOOD:     return "Bom";
        case AirLevel::MODERATE: return "Moderado";
        case AirLevel::POOR:     return "Ruim";
        case AirLevel::BAD:      return "Pessimo";
        default:                 return "-";
    }
}
