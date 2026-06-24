#include "BatteryManager.h"
#include "AdcManager.h"

// GPIO4 = ADC_CHANNEL_3 (ADC1)
#define BAT_ADC_CHANNEL ADC_CHANNEL_3

// Fator de correção do divisor de tensão (5V são ~2.5V no pino *2.02 = tensão real)
#define BAT_DIVIDER_FACTOR 2.02f

float BatteryManager::readBattery() {
    AdcManager::configChannel(BAT_ADC_CHANNEL);
    int pin_mv = AdcManager::readMilliVolts(BAT_ADC_CHANNEL);
    return (float)pin_mv * BAT_DIVIDER_FACTOR;
}
