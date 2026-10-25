#include "BatteryManager.h"
#include "AdcManager.h"
#include "sdkconfig.h"

#define BAT_ADC_CHANNEL    ((adc_channel_t)CONFIG_GOV_BAT_ADC_CHANNEL)
#define BAT_DIVIDER_FACTOR (CONFIG_GOV_BAT_DIVIDER_X100 / 100.0f)

// LiPo abaixo desse valor está fisicamente destruída (proteção corta em ~3.0V).
// Leituras < MIN sao ADC instavel pos-wake, pin flutuando ou hardware com falha —
// nunca refletem carga real. Retornar -1 sinaliza "indisponivel" pros consumers.
#define BAT_MIN_VALID_MV ((float)CONFIG_GOV_BAT_MIN_VALID_MV)

float BatteryManager::readBattery() {
    static bool configured = false;
    if (!configured) {
        AdcManager::configChannel(BAT_ADC_CHANNEL);
        configured = true;
    }
    int pin_mv = AdcManager::readMilliVolts(BAT_ADC_CHANNEL);
    if (pin_mv < 0) return -1.0f;

    float mv = (float)pin_mv * BAT_DIVIDER_FACTOR;
    if (mv < BAT_MIN_VALID_MV) return -1.0f;
    return mv;
}
