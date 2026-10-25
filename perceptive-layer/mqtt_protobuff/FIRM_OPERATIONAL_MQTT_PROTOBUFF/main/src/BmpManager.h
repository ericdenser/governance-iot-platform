#pragma once
#include <bmp280.h>

class BmpManager {
    private:
        bmp280_t dev;

    public:

        void init(uint8_t addr = BMP280_I2C_ADDRESS_0);
        bool readTemp(float &temp);
        bool readPressure(float &pressure);
        bool readHumidity(float &humidity);
        // pressure em Pa; humidity so com isBme()
        bool readAll(float &temp, float &pressure, float &humidity);
        bool isBme() const { return dev.id == BME280_CHIP_ID; }
};



