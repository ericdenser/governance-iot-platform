#include "BatteryManager.h"
#include "AdcManager.h"

// GPIO4 = ADC_CHANNEL_3 (ADC1)
#define BAT_ADC_CHANNEL ADC_CHANNEL_3

// Fator de correção do divisor de tensão (5V são ~2.5V no pino *2.02 = tensão real)
#define BAT_DIVIDER_FACTOR 2.02f

// LiPo abaixo desse valor está fisicamente destruída (proteção corta em ~3.0V).
// Leituras < MIN sao ADC instavel pos-wake, pin flutuando ou hardware com falha —
// nunca refletem carga real. Retornar -1 sinaliza "indisponivel" pros consumers.
#define BAT_MIN_VALID_MV 2600.0f

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
