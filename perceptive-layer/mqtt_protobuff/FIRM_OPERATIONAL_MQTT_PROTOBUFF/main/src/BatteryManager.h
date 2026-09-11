#pragma once
#include <bmp280.h>

class BatteryManager {
public:
    static float readBattery();
};
