#pragma once

#include <stdint.h>
#include "Mq135Manager.h"

class BmpManager;

struct SensorSnapshot {
    int64_t  sampled_us;     // 0 = ainda nao amostrou

    bool     env_valid;
    float    temp_c;
    float    press_hpa;
    bool     hum_valid;      // so BME280
    float    hum_pct;

    bool     air_valid;
    bool     air_warming;
    int      air_warmup_left_s;
    float    co2_ppm;
    AirLevel air_level;

    bool     chip_valid;
    float    chip_c;

    bool     bat_valid;
    float    bat_mv;
};

// Unico leitor do BME/MQ135/chip. UI e telemetria leem daqui.
namespace SensorCache {

constexpr int SAMPLE_PERIOD_MS = 2000;

// false = snapshot mais velho que max_age_ms (task nao rodou ou travou)
bool getFresh(SensorSnapshot& out, int max_age_ms);

void init();

// bme = nullptr se nao detectado
void start(BmpManager* bme);

SensorSnapshot get();

// Bateria e lida pelos hooks do core
void setBattery(float mv);

}  // namespace SensorCache
